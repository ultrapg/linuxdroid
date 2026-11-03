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

sock_base = f"com.linuxdroid.test.{uuid.uuid4().hex[:12]}.root"
server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
server.bind("\0" + sock_base)
server.listen(16)

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

def accept_loop():
    while not stop_flag:
        try:
            conn, _ = server.accept()
        except OSError:
            break
        t = threading.Thread(target=handle_client, args=(conn,), daemon=True)
        t.start()

accept_thread = threading.Thread(target=accept_loop, daemon=True)
accept_thread.start()

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

finally:
    stop_flag = True
    server.close()
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
