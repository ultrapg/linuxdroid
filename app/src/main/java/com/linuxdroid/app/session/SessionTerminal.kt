package com.linuxdroid.app.session

import android.content.Context
import com.linuxdroid.app.runtime.LinuxRuntime
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

        val cmd = LinuxRuntime.prootPrefix(context, root, home, fakeRoot = asRoot)
        if (asRoot) cmd.add(1, "--link2symlink")
        for (spec in LinuxRuntime.lastBinds(context)) {
            if (!spec.startsWith("/proc/self/fd/")) {
                cmd.add("-b")
                cmd.add(spec)
            }
        }
        cmd.addAll(
            listOf(
                "/usr/bin/env",
                "-i",
                "HOME=$home",
                "USER=$user",
                "LOGNAME=$user",
                "TERM=xterm-256color",
                "COLORTERM=truecolor",
                "LANG=C.UTF-8",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "XDG_RUNTIME_DIR=${LinuxRuntime.GUEST_RUNTIME_DIR}",
                "WAYLAND_DISPLAY=wayland-0",
                "DISPLAY=:0",
                "PULSE_SERVER=unix:${File(context.filesDir, "pulseaudio/PS0").absolutePath}",
                "/bin/bash",
                "--login",
            )
        )

        val hostEnv = buildList {
            add("PROOT_LOADER=${LinuxRuntime.prootLoader(context).absolutePath}")
            add("PROOT_TMP_DIR=${LinuxRuntime.prootTempDir(context).absolutePath}")
            if (SessionPrefs.prootNoSeccomp(context)) add("PROOT_NO_SECCOMP=1")
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
