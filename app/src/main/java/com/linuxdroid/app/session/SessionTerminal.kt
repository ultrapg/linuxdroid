package com.linuxdroid.app.session

import android.content.Context
import com.linuxdroid.app.runtime.CpuInfo
import com.linuxdroid.app.runtime.LinuxRuntime
import com.linuxdroid.app.runtime.ProotFastPath
import com.linuxdroid.app.runtime.UserProvisioner
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import java.io.File

object SessionTerminal {
    fun createShellSession(
        context: Context,
        asRoot: Boolean,
        client: TerminalSessionClient,
    ): TerminalSession? {
        if (!LinuxRuntime.isReady(context)) return null
        val root = LinuxRuntime.rootDir(context)
        val user = if (asRoot) "root" else SessionPrefs.username(context)
        val home = if (asRoot) "/root" else "/home/${UserProvisioner.sanitizeUsername(user)}"
        File(root, home.removePrefix("/")).mkdirs()

        val cpuTopology = CpuInfo.detect()
        val affinityCores = if (!asRoot) SessionPrefs.resolveSelectedCpuCores(context, cpuTopology) else emptyList()
        val effectiveCores = if (affinityCores.isNotEmpty()) affinityCores.size else cpuTopology.totalCores

        val binds = LinuxRuntime.lastBinds(context).filter { !it.startsWith("/proc/self/fd/") }
        val useNoSeccomp = SessionPrefs.prootNoSeccomp(context)
        val fastPathKey = if (!asRoot && SessionPrefs.prootFastPath(context) && !useNoSeccomp) {
            ProotFastPath.key(root, binds)
        } else {
            null
        }

        val cmd = LinuxRuntime.prootPrefix(context, root, home, fakeRoot = asRoot)
        if (asRoot) cmd.add(1, "--link2symlink")
        for (spec in binds) {
            cmd.add("-b")
            cmd.add(spec)
        }
        cmd.add("/usr/bin/env")
        cmd.add("-i")
        cmd.add("HOME=$home")
        cmd.add("USER=$user")
        cmd.add("LOGNAME=$user")
        cmd.add("TERM=xterm-256color")
        cmd.add("COLORTERM=truecolor")
        cmd.add("LANG=C.UTF-8")
        cmd.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin")
        cmd.add("GLIBC_TUNABLES=${SessionPrefs.GLIBC_TUNABLES}")
        cmd.add("XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}")
        cmd.add("WAYLAND_DISPLAY=wayland-0")
        cmd.add("DISPLAY=:0")
        cmd.add("PULSE_SERVER=unix:${File(context.filesDir, "pulseaudio/PS0").absolutePath}")
        cmd.add("BL_CPU_COUNT=$effectiveCores")
        if (affinityCores.isNotEmpty()) {
            cmd.add("BL_CPU_CORES=${affinityCores.joinToString(",")}")
        }
        if (fastPathKey != null) {
            for ((k, v) in ProotFastPath.guestEnv(root, binds, fastPathKey)) {
                cmd.add("$k=$v")
            }
        }
        if (affinityCores.isNotEmpty() && File(root, "usr/bin/taskset").isFile) {
            cmd.add("/usr/bin/taskset")
            cmd.add("-c")
            cmd.add(affinityCores.joinToString(","))
        }
        cmd.add("/bin/bash")
        cmd.add("--login")

        val hostEnv = buildList {
            add("PROOT_LOADER=${LinuxRuntime.prootLoader(context).absolutePath}")
            add("PROOT_TMP_DIR=${LinuxRuntime.prootTempDir(context).absolutePath}")
            if (useNoSeccomp) add("PROOT_NO_SECCOMP=1")
            if (fastPathKey != null) {
                val (k, v) = ProotFastPath.hostEnv(fastPathKey)
                add("$k=$v")
            }
        }.toTypedArray()

        return TerminalSession(
            cmd.first(),
            root.absolutePath,
            cmd.toTypedArray(),
            hostEnv,
            4000,
            client,
        ).also {
            it.mSessionName = if (asRoot) "LinuxDroid Root Shell" else "LinuxDroid ($user)"
        }
    }
}
