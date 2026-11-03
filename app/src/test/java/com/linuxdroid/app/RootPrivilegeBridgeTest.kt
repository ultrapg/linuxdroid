package com.linuxdroid.app

import com.linuxdroid.app.runtime.RootSpawner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

class RootPrivilegeBridgeTest {

    private fun findAssetsLinuxfs(): File {
        val dir = listOf(
            File("src/main/assets/linuxfs"),
            File("app/src/main/assets/linuxfs"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected src/main/assets/linuxfs directory", dir)
        return dir!!
    }

    @Test
    fun normalizeSocketNameStripsLeadingAtAndNullPrefix() {
        assertEquals("com.linuxdroid.app.root", RootSpawner.normalizeSocketName("@com.linuxdroid.app.root"))
        assertEquals("com.linuxdroid.app.root", RootSpawner.normalizeSocketName("\u0000com.linuxdroid.app.root"))
        assertEquals("com.linuxdroid.app.root", RootSpawner.normalizeSocketName("  @com.linuxdroid.app.root\n"))
        assertEquals("com.linuxdroid.app.root", RootSpawner.normalizeSocketName(""))
        assertEquals("com.linuxdroid.app.root", RootSpawner.normalizeSocketName(null))
        assertEquals("custom.sock", RootSpawner.normalizeSocketName("@@custom.sock"))
    }

    @Test
    fun manifestMatchesAllBundledLinuxfsAssets() {
        val assetsRoot = findAssetsLinuxfs()
        val manifestFile = File(assetsRoot, "MANIFEST.txt")
        assertTrue(manifestFile.isFile)

        val manifestPaths = LinkedHashSet<String>()
        for (raw in manifestFile.readLines()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"), limit = 2)
            assertEquals("Malformed MANIFEST line: $line", 2, parts.size)
            val mode = parts[0].toIntOrNull(8)
            assertNotNull("Invalid octal mode in MANIFEST: $line", mode)
            val relPath = parts[1]
            val target = File(assetsRoot, relPath)
            assertTrue("Missing file listed in MANIFEST.txt: $relPath", target.isFile && target.length() > 0L)
            manifestPaths.add(relPath)
        }

        assertTrue(manifestPaths.contains("usr/local/bin/linuxdroid-root"))
        assertTrue(manifestPaths.contains("usr/local/bin/sudo"))
        assertTrue(manifestPaths.contains("usr/local/bin/su"))
        assertTrue(manifestPaths.contains("usr/local/bin/pkexec"))
        assertTrue(manifestPaths.contains("usr/local/bin/apt"))
        assertTrue(manifestPaths.contains("usr/local/bin/apt-get"))
        assertTrue(manifestPaths.contains("usr/local/bin/bwrap"))
        assertTrue(manifestPaths.contains("usr/local/bin/flatpak"))
        assertTrue(manifestPaths.contains("usr/local/bin/flatpak-spawn"))
        assertTrue(manifestPaths.contains("usr/local/bin/linuxdroid-apps"))
        assertTrue(manifestPaths.contains("usr/sbin/policy-rc.d"))
        assertTrue(manifestPaths.contains("etc/apt/apt.conf.d/99-linuxdroid-container"))

        val actualFiles = assetsRoot.walkTopDown()
            .filter { it.isFile && it.name != "MANIFEST.txt" }
            .map { it.relativeTo(assetsRoot).invariantSeparatorsPath }
            .toSet()
        assertEquals("All files under assets/linuxfs must be listed in MANIFEST.txt", actualFiles, manifestPaths)
    }

    @Test
    fun sudoNeverDelegatesToSetuidUsrBinSudo() {
        val assetsRoot = findAssetsLinuxfs()
        val sudoText = File(assetsRoot, "usr/local/bin/sudo").readText()
        assertFalse(
            "sudo wrapper must never exec /usr/bin/sudo because Android /data is mounted nosuid",
            sudoText.contains("/usr/bin/sudo")
        )
    }

    @Test
    fun endToEndLinuxdroidRootSudoSuPkexecAndAptAgainstUnixSocketSpawner() {
        val assetsRoot = findAssetsLinuxfs().canonicalFile
        val binDir = File(assetsRoot, "usr/local/bin").canonicalPath

        val pyTest = """
import array
import json
import os
import signal
import socket
import struct
import subprocess
import sys
import tempfile
import threading
import uuid

sys.dont_write_bytecode = True
os.environ["PYTHONDONTWRITEBYTECODE"] = "1"

bin_dir = sys.argv[1]
linuxdroid_root = os.path.join(bin_dir, "linuxdroid-root")
sudo_bin = os.path.join(bin_dir, "sudo")
su_bin = os.path.join(bin_dir, "su")
pkexec_bin = os.path.join(bin_dir, "pkexec")
apt_bin = os.path.join(bin_dir, "apt")
bwrap_bin = os.path.join(bin_dir, "bwrap")
flatpak_bin = os.path.join(bin_dir, "flatpak")

sock_base = f"com.linuxdroid.test.{uuid.uuid4().hex[:12]}.root"
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind("\0" + sock_base)
server.listen(16)

fs_sock_dir = tempfile.mkdtemp(prefix="ld-root-fs-")
fs_sock_path = os.path.join(fs_sock_dir, "root.sock")
fs_server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
fs_server.bind(fs_sock_path)
fs_server.listen(16)

recorded_requests = []
stop_flag = False

def handle_client(conn):
    with conn:
        fds = array.array("i")
        msg, ancdata, flags, _ = conn.recvmsg(5, socket.CMSG_SPACE(3 * fds.itemsize))
        if not msg:
            return
        for cmsg_lvl, cmsg_type, cmsg_data in ancdata:
            if cmsg_lvl == socket.SOL_SOCKET and cmsg_type == socket.SCM_RIGHTS:
                fds.frombytes(cmsg_data[:len(cmsg_data) - (len(cmsg_data) % fds.itemsize)])
        kind = chr(msg[0])
        length = struct.unpack(">i", msg[1:5])[0]
        body = b""
        while len(body) < length:
            chunk = conn.recv(length - len(body))
            if not chunk:
                break
            body += chunk
        req = json.loads(body.decode("utf-8"))
        recorded_requests.append(req)

        argv = req["argv"]
        env = dict(req.get("env", {}))
        env["FAKE_ROOT_UID"] = "0"
        cwd = req.get("cwd") or "/"

        # Spawn real subprocess wired to the passed SCM_RIGHTS file descriptors
        proc = subprocess.Popen(
            argv,
            stdin=fds[0],
            stdout=fds[1],
            stderr=fds[2],
            cwd=cwd if os.path.isdir(cwd) else "/",
            env=env,
            close_fds=True,
        )
        for fd in fds:
            try:
                os.close(fd)
            except OSError:
                pass
        pid_bytes = str(proc.pid).encode()
        conn.sendall(b"P" + struct.pack(">i", len(pid_bytes)) + pid_bytes)
        rc = proc.wait()
        rc_bytes = str(rc).encode()
        conn.sendall(b"X" + struct.pack(">i", len(rc_bytes)) + rc_bytes)

def accept_loop(srv):
    while not stop_flag:
        try:
            conn, _ = srv.accept()
        except OSError:
            break
        t = threading.Thread(target=handle_client, args=(conn,), daemon=True)
        t.start()

accept_thread = threading.Thread(target=accept_loop, args=(server,), daemon=True)
accept_thread.start()
fs_accept_thread = threading.Thread(target=accept_loop, args=(fs_server,), daemon=True)
fs_accept_thread.start()

try:
    # 1. Test with leading '@' in LINUXDROID_ROOT_SOCKET (exact regression from screenshot)
    env_with_at = dict(os.environ)
    env_with_at["LINUXDROID_ROOT_SOCKET"] = "@" + sock_base
    env_with_at["USER"] = "droid"
    env_with_at["HOME"] = "/tmp"

    res = subprocess.run(
        [linuxdroid_root, "--check"],
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert res.returncode == 0, f"linuxdroid-root --check failed: {res.stderr}"

    # 1b. Test filesystem socket path in LINUXDROID_ROOT_SOCKET (/run/linuxdroid/root.sock fallback)
    env_fs = dict(env_with_at)
    env_fs["LINUXDROID_ROOT_SOCKET"] = fs_sock_path
    res_fs = subprocess.run(
        [sudo_bin, "python3", "-c", "print('FS_SOCKET_OK')"],
        env=env_fs,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert res_fs.returncode == 0, f"Filesystem socket sudo failed: {res_fs.stderr}"
    assert res_fs.stdout.strip() == "FS_SOCKET_OK"

    # 2. Test sudo command execution and stdout/stderr pipe capture
    res = subprocess.run(
        [sudo_bin, "python3", "-c", "import os, sys; print(os.environ.get('USER'), os.environ.get('SUDO_USER'), os.environ.get('HOME')); sys.exit(42)"],
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert res.returncode == 42, f"Expected exit code 42, got {res.returncode} (stderr={res.stderr})"
    assert res.stdout.strip() == "root droid /root", f"Unexpected stdout: {res.stdout!r}"

    # 3. Test sudo flags: -V, -h, -l, -v, -k, -i, -s, -uroot, -D, KEY=VAL
    for flag in (["-V"], ["--version"], ["-h"], ["--help"], ["-l"], ["-l", "sh"], ["-v"], ["-k"], ["-K"]):
        r = subprocess.run([sudo_bin] + flag, env=env_with_at, capture_output=True, text=True, timeout=5)
        assert r.returncode == 0, f"sudo {flag} failed with {r.returncode}: {r.stderr}"

    r_env = subprocess.run(
        [sudo_bin, "FOO_BAR=baz_val", "-D", "/tmp", "-uroot", "python3", "-c", "import os; print(os.getcwd(), os.environ.get('FOO_BAR'))"],
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_env.returncode == 0, f"sudo FOO_BAR failed: {r_env.stderr}"
    assert r_env.stdout.strip() == f"{os.path.realpath('/tmp')} baz_val", f"Unexpected output: {r_env.stdout!r}"

    # 4. Test stdin pipe forwarding through sudo -> linuxdroid-root -> RootSpawner
    r_stdin = subprocess.run(
        [sudo_bin, "python3", "-c", "import sys; print(sys.stdin.read().upper(), end='')"],
        input="hello flatpak\n",
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_stdin.returncode == 0
    assert r_stdin.stdout == "HELLO FLATPAK\n"

    # 5. Test su wrapper (-c, -, --version, --help)
    r_su = subprocess.run(
        [su_bin, "-", "root", "-c", "echo ${'$'}USER:${'$'}HOME"],
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_su.returncode == 0, f"su failed: {r_su.stderr}"
    assert r_su.stdout.strip() == "root:/root", f"Unexpected su output: {r_su.stdout!r}"

    # 6. Test pkexec wrapper
    r_pk = subprocess.run(
        [pkexec_bin, "python3", "-c", "import os; print(os.environ.get('USER'), 'PKEXEC_UID' in os.environ)"],
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_pk.returncode == 0, f"pkexec failed: {r_pk.stderr}"
    assert r_pk.stdout.strip() == "root True", f"Unexpected pkexec output: {r_pk.stdout!r}"

    # 7. Test closed stdin fd (fd 0 closed) recovery in linuxdroid-root
    r_closed_stdin = subprocess.run(
        ["python3", "-c", f"import os, subprocess; os.close(0); r = subprocess.run([{sudo_bin!r}, 'true']); raise SystemExit(r.returncode)"],
        env=env_with_at,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_closed_stdin.returncode == 0, f"Closed stdin failed: {r_closed_stdin.stderr}"

    # 8. Test interactive PTY forwarding (isatty(0) == True and req['tty'] populated)
    import pty
    master_fd, slave_fd = pty.openpty()
    try:
        r_pty = subprocess.run(
            [sudo_bin, "python3", "-c", "import os; print('ISATTY=' + str(os.isatty(0)))"],
            stdin=slave_fd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=env_with_at,
            text=True,
            timeout=5,
        )
        assert r_pty.returncode == 0, f"PTY sudo failed: {r_pty.stderr}"
        assert "ISATTY=True" in r_pty.stdout, f"Expected ISATTY=True, got {r_pty.stdout!r}"
        assert recorded_requests[-1].get("tty", "").startswith("/dev/pts/"), f"Expected /dev/pts/* in req['tty'], got {recorded_requests[-1]}"
    finally:
        os.close(master_fd)
        os.close(slave_fd)

    # 9. Test apt wrapper subcommand parser and empty lists detection
    import importlib.util, importlib.machinery
    apt_loader = importlib.machinery.SourceFileLoader("apt_wrapper", apt_bin)
    apt_spec = importlib.util.spec_from_loader("apt_wrapper", apt_loader)
    apt_mod = importlib.util.module_from_spec(apt_spec)
    apt_loader.exec_module(apt_mod)
    assert apt_mod.find_subcommand(["-y", "-o", "APT::Sandbox::User=root", "install", "flatpak"]) == "install"
    assert apt_mod.find_subcommand(["update"]) == "update"
    assert apt_mod.find_subcommand(["-qq", "full-upgrade"]) == "full-upgrade"
    assert apt_mod.find_subcommand(["--version"]) is None

    with tempfile.TemporaryDirectory() as tmp_lists:
        apt_mod.LISTS_DIR = tmp_lists
        assert not apt_mod.has_apt_lists(), "Empty dir should report no apt lists"
        with open(os.path.join(tmp_lists, "lock"), "w") as f:
            f.write("")
        os.makedirs(os.path.join(tmp_lists, "partial"), exist_ok=True)
        assert not apt_mod.has_apt_lists(), "Only lock + partial should still report no apt lists"
        with open(os.path.join(tmp_lists, "ports.ubuntu.com_ubuntu-ports_dists_noble_InRelease"), "w") as f:
            f.write("Origin: Ubuntu\n")
        assert apt_mod.has_apt_lists(), "Populated InRelease should report has_apt_lists=True"

    # 10. Test exec_already_root clean error handling (exit 127 on missing command, no traceback)
    root_loader = importlib.machinery.SourceFileLoader("linuxdroid_root_mod", linuxdroid_root)
    root_spec = importlib.util.spec_from_loader("linuxdroid_root_mod", root_loader)
    root_mod = importlib.util.module_from_spec(root_spec)
    root_loader.exec_module(root_mod)
    rc_missing = root_mod.exec_already_root(["/nonexistent_linuxdroid_binary_xyz"])
    assert rc_missing == 127, f"Expected 127 for missing binary, got {rc_missing}"

    # 11. Test bwrap shim (--version, --args FD, --info-fd FD, --setenv, --chdir)
    r_bw_ver = subprocess.run([bwrap_bin, "--version"], capture_output=True, text=True, timeout=5)
    assert r_bw_ver.returncode == 0 and "bubblewrap" in r_bw_ver.stdout
    args_r, args_w = os.pipe()
    info_r, info_w = os.pipe()
    os.write(args_w, b"--unshare-all\0--ro-bind\0/\0/\0--setenv\0BWRAP_TEST_VAR\0ok_val\0--chdir\0/tmp\0--info-fd\0" + str(info_w).encode() + b"\0")
    os.close(args_w)
    os.set_inheritable(args_r, True)
    os.set_inheritable(info_w, True)
    r_bw = subprocess.run(
        [bwrap_bin, "--args", str(args_r), "--", "python3", "-c", "import os; print(os.getcwd(), os.environ.get('BWRAP_TEST_VAR'))"],
        pass_fds=(args_r, info_w),
        capture_output=True,
        text=True,
        timeout=5,
    )
    os.close(args_r)
    os.close(info_w)
    info_json = json.loads(os.read(info_r, 1024).decode())
    os.close(info_r)
    assert r_bw.returncode == 0, f"bwrap failed: {r_bw.stderr}"
    assert r_bw.stdout.strip() == f"{os.path.realpath('/tmp')} ok_val"
    assert "child-pid" in info_json

    # 12. Test flatpak wrapper subcommand & scope parser
    fp_loader = importlib.machinery.SourceFileLoader("flatpak_wrapper", flatpak_bin)
    fp_spec = importlib.util.spec_from_loader("flatpak_wrapper", fp_loader)
    fp_mod = importlib.util.module_from_spec(fp_spec)
    fp_loader.exec_module(fp_mod)
    assert fp_mod.find_subcommand_and_scope(["remote-add", "--if-not-exists", "flathub", "https://dl.flathub.org/repo/flathub.flatpakrepo"]) == ("remote-add", False)
    assert fp_mod.find_subcommand_and_scope(["install", "--user", "flathub", "org.gimp.GIMP"]) == ("install", True)
    assert fp_mod.find_subcommand_and_scope(["install", "-y", "flathub", "io.github.flattool.Warehouse"]) == ("install", False)

    # 13. Test flatpak-spawn (--host, --clear-env, --env=KEY=VAL, sandbox path stripping)
    fp_spawn_bin = os.path.join(bin_dir, "flatpak-spawn")
    r_fps_ver = subprocess.run([fp_spawn_bin, "--version"], capture_output=True, text=True, timeout=5)
    assert r_fps_ver.returncode == 0 and "flatpak-spawn" in r_fps_ver.stdout
    env_fps = dict(env_with_at)
    env_fps["LD_LIBRARY_PATH"] = "/app/lib:/tmp/.bwrap-rt-lib-1000:/usr/local/lib"
    r_fps = subprocess.run(
        [
            fp_spawn_bin,
            "--host",
            "--clear-env",
            "--env=WAREHOUSE_TEST=ok",
            "--directory=/tmp",
            "python3",
            "-c",
            "import os; print(os.getcwd(), os.environ.get('WAREHOUSE_TEST'), bool(os.environ.get('LINUXDROID_ROOT_SOCKET')), os.environ.get('LD_LIBRARY_PATH', ''))",
        ],
        env=env_fps,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_fps.returncode == 0, f"flatpak-spawn failed: {r_fps.stderr}"
    assert r_fps.stdout.strip() == f"{os.path.realpath('/tmp')} ok True", f"Unexpected flatpak-spawn output: {r_fps.stdout!r}"

    # 14. Test bwrap runtime library filtering (never symlinks libc.so.6, symlinks unique runtime .so)
    bw_loader = importlib.machinery.SourceFileLoader("bwrap_mod", bwrap_bin)
    bw_spec = importlib.util.spec_from_loader("bwrap_mod", bw_loader)
    bw_mod = importlib.util.module_from_spec(bw_spec)
    bw_loader.exec_module(bw_mod)
    with tempfile.TemporaryDirectory() as fake_usr:
        lib_dir = os.path.join(fake_usr, "lib", "aarch64-linux-gnu")
        os.makedirs(lib_dir, exist_ok=True)
        with open(os.path.join(lib_dir, "libc.so.6"), "w") as f:
            f.write("fake")
        with open(os.path.join(lib_dir, "libunique_flatpak_test.so.1"), "w") as f:
            f.write("unique")
        rt_dir = bw_mod.build_filtered_runtime_libdir(fake_usr)
        assert rt_dir is not None and os.path.isdir(rt_dir)
        assert not os.path.lexists(os.path.join(rt_dir, "libc.so.6")), "libc.so.6 must never be symlinked from Flatpak runtime"
        assert os.path.islink(os.path.join(rt_dir, "libunique_flatpak_test.so.1")), "Unique runtime .so should be symlinked"
        os.unlink(os.path.join(rt_dir, "libunique_flatpak_test.so.1"))

    # 15. Test linuxdroid-apps --list outputs all 16 workstation setup options including Warehouse (Flatpak Store)
    apps_bin = os.path.join(bin_dir, "linuxdroid-apps")
    r_apps = subprocess.run([apps_bin, "--list"], capture_output=True, text=True, timeout=5)
    assert r_apps.returncode == 0, f"linuxdroid-apps --list failed: {r_apps.stderr}"
    assert "Warehouse (Flatpak Store)" in r_apps.stdout
    for expected_id in (
        "warehouse",
        "chromium",
        "fex-rootfs",
        "vscode",
        "vscodium",
        "blender",
        "krita",
        "obs-studio",
        "kdenlive",
        "libreoffice",
        "gimp",
        "devtools",
        "rust",
        "golang",
        "openjdk",
        "docker-cli",
        "podman",
    ):
        assert expected_id in r_apps.stdout, f"Missing {expected_id} in linuxdroid-apps --list"


finally:
    stop_flag = True
    server.close()
    fs_server.close()
    try:
        os.unlink(fs_sock_path)
        os.rmdir(fs_sock_dir)
    except OSError:
        pass
""".trimIndent()

        val proc = ProcessBuilder("python3", "-c", pyTest, binDir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        assertTrue("Python integration test timed out:\n$output", finished)
        assertEquals("Python integration test failed:\n$output", 0, proc.exitValue())
    }
}
