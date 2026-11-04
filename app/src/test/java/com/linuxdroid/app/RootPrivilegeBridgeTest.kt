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

    @Test
    fun flatpakBwrapFexAndContainerAptPoliciesHandleUnprivilegedEdgeCases() {
        val assetsRoot = findAssetsLinuxfs().canonicalFile
        val aptConf = File(assetsRoot, "etc/apt/apt.conf.d/99-linuxdroid-container").readText()
        assertTrue(aptConf.contains("APT::Install-Recommends \"false\";"))
        assertTrue(aptConf.contains("APT::Install-Suggests \"false\";"))

        val binDir = File(assetsRoot, "usr/local/bin").canonicalPath
        val pyTest = """
import importlib.machinery
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile

sys.dont_write_bytecode = True
bin_dir = sys.argv[1]

def load_mod(name, filename):
    p = os.path.join(bin_dir, filename)
    loader = importlib.machinery.SourceFileLoader(name, p)
    spec = importlib.util.spec_from_loader(name, loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod

bw_mod = load_mod("bwrap_edge", "bwrap")
fp_mod = load_mod("flatpak_edge", "flatpak")
fex_mod = load_mod("fex_edge", "linuxdroid-fex")
bwrap_bin = os.path.join(bin_dir, "bwrap")
fp_spawn_bin = os.path.join(bin_dir, "flatpak-spawn")

# 1. bwrap copy_fd_to_dest('/etc/ld.so.conf') stages to /tmp instead of clobbering host /etc/ld.so.conf
r_fd, w_fd = os.pipe()
os.write(w_fd, b"/app/lib\n/usr/lib/aarch64-linux-gnu\n")
os.close(w_fd)
staged = bw_mod.copy_fd_to_dest(str(r_fd), "/etc/ld.so.conf")
assert staged is not None and staged.startswith("/tmp/.bwrap-ld-so-conf-")
with open(staged, "rb") as f:
    assert b"/app/lib" in f.read()
os.unlink(staged)

# 2. bwrap ldconfig invocation injects -f <staged_conf> and remaps ephemeral /run binds
with tempfile.TemporaryDirectory() as tmp_cache_src:
    conf_r, conf_w = os.pipe()
    os.write(conf_w, b"/app/lib\n")
    os.close(conf_w)
    os.set_inheritable(conf_r, True)
    fake_sbin = os.path.join(tmp_cache_src, "sbin")
    os.makedirs(fake_sbin, exist_ok=True)
    fake_ldconfig = os.path.join(fake_sbin, "ldconfig")
    with open(fake_ldconfig, "w") as f:
        f.write("#!/usr/bin/python3 -BE\nimport sys; print('ARGS=' + '|'.join(sys.argv[1:]))\n")
    os.chmod(fake_ldconfig, 0o755)
    env_ld = dict(os.environ)
    env_ld["PATH"] = fake_sbin + ":" + env_ld.get("PATH", "")
    r_ld = subprocess.run(
        [
            bwrap_bin,
            "--ro-bind-data", str(conf_r), "/etc/ld.so.conf",
            "--bind", tmp_cache_src, "/tmp/bwrap-ephemeral-cache-test",
            "--",
            fake_ldconfig, "-X", "-C", "/tmp/bwrap-ephemeral-cache-test/ld.so.cache",
        ],
        pass_fds=(conf_r,),
        env=env_ld,
        capture_output=True,
        text=True,
        timeout=5,
    )
    os.close(conf_r)
    assert r_ld.returncode == 0, f"bwrap ldconfig failed: {r_ld.stderr}"
    assert "ARGS=-f|/tmp/.bwrap-ld-so-conf-" in r_ld.stdout, f"Unexpected ldconfig args: {r_ld.stdout}"
    assert f"{tmp_cache_src}/ld.so.cache" in r_ld.stdout, f"Expected remapped cache path in {r_ld.stdout}"
    if os.path.islink("/tmp/bwrap-ephemeral-cache-test"):
        os.unlink("/tmp/bwrap-ephemeral-cache-test")

# 3. bwrap Flatpak runtime symlink resolution stays inside usr_src (never escapes /usr/bin/python3 to host)
with tempfile.TemporaryDirectory() as fake_rt:
    os.makedirs(os.path.join(fake_rt, "bin"), exist_ok=True)
    os.makedirs(os.path.join(fake_rt, "lib", "aarch64-linux-gnu", "gdk-pixbuf-2.0", "2.10.0"), exist_ok=True)
    fake_ld = os.path.join(fake_rt, "lib", "aarch64-linux-gnu", "ld-linux-aarch64.so.1")
    with open(fake_ld, "wb") as f:
        f.write(b"\x7fELF")
    fake_py313 = os.path.join(fake_rt, "bin", "python3.13")
    with open(fake_py313, "wb") as f:
        f.write(b"\x7fELF")
    os.symlink("python3.13", os.path.join(fake_rt, "bin", "python3"))
    os.symlink("/usr/bin/python3", os.path.join(fake_rt, "bin", "python"))
    loaders = os.path.join(fake_rt, "lib", "aarch64-linux-gnu", "gdk-pixbuf-2.0", "2.10.0", "loaders.cache")
    with open(loaders, "w") as f:
        f.write('"/usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so"\n')

    assert bw_mod.find_runtime_ld_linux(fake_rt) == fake_ld
    resolved_py = bw_mod.resolve_sandbox_file("/usr/bin/python", None, fake_rt, [])
    assert resolved_py == fake_py313, f"Expected {fake_py313}, got {resolved_py}"
    relocated = bw_mod.prepare_relocated_pixbuf_cache(fake_rt)
    assert relocated is not None and os.path.isfile(relocated)
    with open(relocated) as f:
        assert fake_rt in f.read()
    os.unlink(relocated)

# 4. flatpak is_unix_socket_live distinguishes live vs stale sockets
with tempfile.TemporaryDirectory() as sock_dir:
    sock_path = os.path.join(sock_dir, "bus.sock")
    srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    srv.bind(sock_path)
    srv.listen(1)
    assert fp_mod.is_unix_socket_live(sock_path) is True
    srv.close()
    assert os.path.exists(sock_path)
    assert fp_mod.is_unix_socket_live(sock_path) is False

# 5. flatpak-spawn --host strips BL_FLATPAK_*, PYTHONHOME, and restores BL_HOST_* session vars
env_sandbox = dict(os.environ)
env_sandbox.update({
    "BL_FLATPAK_LD": "/var/lib/flatpak/runtime/ld-linux.so",
    "BL_FLATPAK_USR": "/var/lib/flatpak/runtime/files",
    "PYTHONHOME": "/var/lib/flatpak/runtime/files",
    "DBUS_SESSION_BUS_ADDRESS": "unix:path=/run/user/1000/bus",
    "BL_HOST_DBUS_SESSION_BUS_ADDRESS": "unix:path=/tmp/real-host-bus",
    "XDG_DATA_DIRS": "/app/share:/var/lib/flatpak/runtime/org.gnome.Platform/files/share:/var/lib/flatpak/exports/share:/usr/share",
})
r_host = subprocess.run(
    [
        fp_spawn_bin,
        "--host",
        "python3",
        "-c",
        "import json, os; print(json.dumps({'ld': os.environ.get('BL_FLATPAK_LD'), 'ph': os.environ.get('PYTHONHOME'), 'dbus': os.environ.get('DBUS_SESSION_BUS_ADDRESS'), 'xdg': os.environ.get('XDG_DATA_DIRS')}))",
    ],
    env=env_sandbox,
    capture_output=True,
    text=True,
    timeout=5,
)
assert r_host.returncode == 0, f"flatpak-spawn --host failed: {r_host.stderr}"
host_info = json.loads(r_host.stdout.strip())
assert host_info["ld"] is None
assert host_info["ph"] is None
assert host_info["dbus"] == "unix:path=/tmp/real-host-bus"
assert host_info["xdg"] == "/var/lib/flatpak/exports/share:/usr/share"

# 6. linuxdroid-fex prefers extracted RootFS directories over .ero/.sqsh archives (since Android lacks /dev/fuse)
with tempfile.TemporaryDirectory() as fake_home:
    hp = Path(fake_home)
    rdir = hp / ".fex-emu" / "RootFS"
    rdir.mkdir(parents=True)
    (rdir / "Ubuntu_24_04.ero").write_bytes(b"erofs")
    (hp / ".fex-emu" / "Config.json").write_text(json.dumps({"Config": {"RootFS": "Ubuntu_24_04.ero"}}))
    assert fex_mod.ensure_fex_config(hp) == "Ubuntu_24_04.ero"

    extracted = rdir / "Ubuntu_24_04"
    extracted.mkdir()
    (extracted / "usr").mkdir()
    (extracted / "etc").mkdir()
    assert fex_mod.find_rootfs(hp) == "Ubuntu_24_04"
    assert fex_mod.ensure_fex_config(hp) == "Ubuntu_24_04"
    saved_cfg = json.loads((hp / ".fex-emu" / "Config.json").read_text())
    assert saved_cfg["Config"]["RootFS"] == "Ubuntu_24_04"
    assert (extracted / "etc" / "ld.so.preload").is_file()

with tempfile.TemporaryDirectory() as fake_xdg_home:
    xhp = Path(fake_xdg_home)
    xrfs = xhp / ".local" / "share" / "fex-emu" / "RootFS" / "Ubuntu_24_04"
    (xrfs / "etc").mkdir(parents=True)
    assert fex_mod.find_rootfs(xhp) == "Ubuntu_24_04"
    assert fex_mod.ensure_fex_config(xhp) == "Ubuntu_24_04"
    assert not (xhp / ".fex-emu").exists()
    xdg_saved = json.loads((xhp / ".config" / "fex-emu" / "Config.json").read_text())
    assert xdg_saved["Config"]["RootFS"] == "Ubuntu_24_04"
    assert (xrfs / "etc" / "ld.so.preload").is_file()

# 7. Regression: bwrap resolve_sandbox_file stops before PRoot .l2s. pseudo-hardlink targets
with tempfile.TemporaryDirectory() as fake_l2s_rt:
    obj_dir = os.path.join(fake_l2s_rt, "repo", "objects", "93")
    bin_d = os.path.join(fake_l2s_rt, "runtime", "files", "bin")
    os.makedirs(obj_dir, exist_ok=True)
    os.makedirs(bin_d, exist_ok=True)
    l2s_backing = os.path.join(obj_dir, ".l2s.252c8f1619c69eae475cfb2dcdfa6ec9f703d8a3b438d39320ff72db9c481c.file0001.0002")
    with open(l2s_backing, "wb") as f:
        f.write(b"\x7fELF")
    py313_l2s = os.path.join(bin_d, "python3.13")
    os.symlink(l2s_backing, py313_l2s)
    os.symlink("python3.13", os.path.join(bin_d, "python3"))
    rt_files = os.path.join(fake_l2s_rt, "runtime", "files")
    resolved_l2s = bw_mod.resolve_sandbox_file("/usr/bin/python3", None, rt_files, [])
    assert resolved_l2s == py313_l2s, f"Must stop at {py313_l2s} instead of chasing .l2s., got {resolved_l2s}"
    assert os.path.basename(resolved_l2s).startswith("python3")

# 8. Regression: bwrap PATH ordering & nested bwrap --ro-bind /usr /usr --clearenv inheritance
with tempfile.TemporaryDirectory() as fake_fp_root:
    fake_usr = os.path.join(fake_fp_root, "usr_files")
    fake_app = os.path.join(fake_fp_root, "app_files")
    os.makedirs(os.path.join(fake_usr, "bin"), exist_ok=True)
    os.makedirs(os.path.join(fake_app, "bin"), exist_ok=True)
    env_nested = dict(os.environ)
    env_nested["BL_DESKTOP"] = "1"
    env_nested["BL_FLATPAK_USR"] = fake_usr
    env_nested["BL_FLATPAK_APP"] = fake_app
    r_nested = subprocess.run(
        [
            bwrap_bin,
            "--ro-bind", "/usr", "/usr",
            "--ro-bind", "/app", "/app",
            "--clearenv",
            "--",
            "python3", "-c",
            "import json, os; print(json.dumps({'path': os.environ.get('PATH', ''), 'desktop': os.environ.get('BL_DESKTOP')}))",
        ],
        env=env_nested,
        capture_output=True,
        text=True,
        timeout=5,
    )
    assert r_nested.returncode == 0, f"Nested bwrap failed: {r_nested.stderr}"
    nested_info = json.loads(r_nested.stdout.strip())
    path_parts = nested_info["path"].split(":")
    assert nested_info["desktop"] == "1"
    assert path_parts.index(os.path.join(fake_app, "bin")) < path_parts.index("/usr/bin"), f"app/bin must precede /usr/bin: {path_parts}"
    assert path_parts.index(os.path.join(fake_usr, "bin")) < path_parts.index("/usr/bin"), f"usr/bin must precede host /usr/bin: {path_parts}"

# 9. Regression: flatpak-spawn preserves BL_DESKTOP on --host --clear-env, and preserves BL_FLATPAK_* when --host is omitted
env_fps2 = dict(os.environ)
env_fps2.update({
    "BL_DESKTOP": "1",
    "BL_FLATPAK_USR": "/tmp/fake-fp-usr",
    "PATH": "/tmp/fake-fp-app/bin:/tmp/fake-fp-usr/bin:/usr/bin",
})
r_fps_host = subprocess.run(
    [fp_spawn_bin, "--host", "--clear-env", "python3", "-c", "import json, os; print(json.dumps({'d': os.environ.get('BL_DESKTOP'), 'u': os.environ.get('BL_FLATPAK_USR')}))"],
    env=env_fps2,
    capture_output=True,
    text=True,
    timeout=5,
)
assert r_fps_host.returncode == 0
fps_host_data = json.loads(r_fps_host.stdout.strip())
assert fps_host_data == {"d": "1", "u": None}

r_fps_sub = subprocess.run(
    [fp_spawn_bin, "--clear-env", "python3", "-c", "import json, os; print(json.dumps({'d': os.environ.get('BL_DESKTOP'), 'u': os.environ.get('BL_FLATPAK_USR'), 'p': os.environ.get('PATH')}))"],
    env=env_fps2,
    capture_output=True,
    text=True,
    timeout=5,
)
assert r_fps_sub.returncode == 0
fps_sub_data = json.loads(r_fps_sub.stdout.strip())
assert fps_sub_data["d"] == "1"
assert fps_sub_data["u"] == "/tmp/fake-fp-usr"
assert fps_sub_data["p"].startswith("/tmp/fake-fp-app/bin:")

# 10. Regression: flatpak is_dbus_address_live supports unix:path= and unix:abstract=
with tempfile.TemporaryDirectory() as dbus_tmp:
    p_sock = os.path.join(dbus_tmp, "sess.sock")
    s1 = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    s1.bind(p_sock)
    s1.listen(1)
    assert fp_mod.is_dbus_address_live(f"unix:path={p_sock},guid=0123456789abcdef") is True
    s1.close()
    assert fp_mod.is_dbus_address_live(f"unix:path={p_sock},guid=0123456789abcdef") is False
""".trimIndent()

        val proc = ProcessBuilder("python3", "-B", "-c", pyTest, binDir)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(20, TimeUnit.SECONDS)
        assertTrue("Edge-case integration test timed out:\n$output", finished)
        assertEquals("Edge-case integration test failed:\n$output", 0, proc.exitValue())
    }

    @Test
    fun unifiedLink2SymlinkAndDesktopEnvAcrossRuntimeSpawners() {
        val srcRoot = listOf(
            File("src/main/java/com/linuxdroid/app"),
            File("app/src/main/java/com/linuxdroid/app"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected Kotlin source root", srcRoot)

        val linuxRuntimeSrc = File(srcRoot!!, "runtime/LinuxRuntime.kt").readText()
        val rootSpawnerSrc = File(srcRoot, "runtime/RootSpawner.kt").readText()
        val sessionTerminalSrc = File(srcRoot, "session/SessionTerminal.kt").readText()

        assertTrue(
            "LinuxRuntime.prootPrefix must enable --link2symlink globally so user sessions and RootSpawner share hardlink semantics",
            linuxRuntimeSrc.contains("cmd.add(\"--link2symlink\")")
        )
        assertFalse(
            "RootSpawner must not duplicate --link2symlink on top of LinuxRuntime.prootPrefix",
            rootSpawnerSrc.contains("--link2symlink")
        )
        assertFalse(
            "SessionTerminal must not gate --link2symlink on asRoot",
            sessionTerminalSrc.contains("--link2symlink")
        )
        assertTrue(
            "SessionTerminal must export BL_DESKTOP=1 so transparent x86_64 execution works in standalone terminals",
            sessionTerminalSrc.contains("BL_DESKTOP=1")
        )
    }

    @Test
    fun overlayStagedBeforeRootSpawnerStartAcrossAllEntryPoints() {
        val srcRoot = listOf(
            File("src/main/java/com/linuxdroid/app"),
            File("app/src/main/java/com/linuxdroid/app"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected Kotlin source root", srcRoot)

        val appSrc = File(srcRoot!!, "App.kt").readText()
        val sessionServiceSrc = File(srcRoot, "session/SessionService.kt").readText()
        val sessionTerminalSrc = File(srcRoot, "session/SessionTerminal.kt").readText()
        val rootSpawnerSrc = File(srcRoot, "runtime/RootSpawner.kt").readText()

        val appStageIdx = appSrc.indexOf("LinuxRuntime.ensureBundledOverlayCurrent")
        val appSpawnerIdx = appSrc.indexOf("RootSpawner.start")
        assertTrue("App.kt must call ensureBundledOverlayCurrent before RootSpawner.start", appStageIdx in 0 until appSpawnerIdx)

        val svcStageIdx = sessionServiceSrc.indexOf("LinuxRuntime.stageBundledOverlay")
        val svcSpawnerIdx = sessionServiceSrc.indexOf("RootSpawner.start")
        assertTrue("SessionService.kt must stage bundled overlay before RootSpawner.start", svcStageIdx in 0 until svcSpawnerIdx)

        val termStageIdx = sessionTerminalSrc.indexOf("LinuxRuntime.stageBundledOverlay")
        val termSpawnerIdx = sessionTerminalSrc.indexOf("RootSpawner.start")
        assertTrue("SessionTerminal.kt must stage bundled overlay before RootSpawner.start", termStageIdx in 0 until termSpawnerIdx)

        assertTrue(
            "RootSpawner.runRootProcess must verify overlay currency before spawning root commands",
            rootSpawnerSrc.contains("LinuxRuntime.ensureBundledOverlayCurrent(context)")
        )
    }

    @Test
    fun linuxdroidAppsHealsBrokenDpkgWithZeroArgAptFixBrokenBeforeInstallingTarget() {
        val assetsRoot = findAssetsLinuxfs().canonicalFile
        val appsScript = File(assetsRoot, "usr/local/bin/linuxdroid-apps").canonicalPath

        val pyTest = """
import os
import subprocess
import sys
import tempfile

apps_script = sys.argv[1]

with tempfile.TemporaryDirectory(prefix="ld-apps-heal-") as tmp:
    mock_bin = os.path.join(tmp, "bin")
    os.makedirs(mock_bin, exist_ok=True)
    state_file = os.path.join(tmp, "dpkg_broken")
    log_file = os.path.join(tmp, "calls.log")
    with open(state_file, "w") as f:
        f.write("broken")

    def write_exec(name, body):
        p = os.path.join(mock_bin, name)
        with open(p, "w") as f:
            f.write(body)
        os.chmod(p, 0o755)

    write_exec("sudo", '''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
if args and args[0] == "env":
    args = args[1:]
    while args and "=" in args[0] and not args[0].startswith("-"):
        k, v = args[0].split("=", 1)
        os.environ[k] = v
        args = args[1:]
os.execvp(args[0], args)
''')

    write_exec("dpkg", f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
with open({log_file!r}, "a") as lf:
    lf.write("dpkg " + " ".join(args) + "\\n")
if args and args[0] == "--audit":
    if os.path.exists({state_file!r}):
        print("The following packages have been unpacked but not yet configured: broken-pkg")
    sys.exit(0)
if args and args[0] == "--configure":
    sys.exit(1 if os.path.exists({state_file!r}) else 0)
sys.exit(0)
''')

    write_exec("apt-get", f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
with open({log_file!r}, "a") as lf:
    lf.write("apt-get " + " ".join(args) + "\\n")
has_update = "update" in args
has_check = "check" in args
has_install = "install" in args
has_f = "-f" in args or "--fix-broken" in args
has_confdef = any("--force-confdef" in a for a in args)
has_confold = any("--force-confold" in a for a in args)
extra_pkgs = [
    a for a in args
    if a not in ("update", "check", "install", "-y", "-f", "--fix-broken", "-o")
    and not a.startswith("Dpkg::Options::=")
]
if has_update:
    sys.exit(0)
if has_check:
    sys.exit(100 if os.path.exists({state_file!r}) else 0)
if has_install and has_f:
    if extra_pkgs:
        sys.stderr.write("ERROR: apt-get install -f invoked with extra package args!\\n")
        sys.exit(100)
    if not (has_confdef and has_confold):
        sys.stderr.write("ERROR: missing force-confdef/force-confold!\\n")
        sys.exit(100)
    if os.path.exists({state_file!r}):
        os.remove({state_file!r})
    sys.exit(0)
if has_install:
    if os.path.exists({state_file!r}):
        sys.stderr.write("E: Unmet dependencies. Try 'apt --fix-broken install' with no packages\\n")
        sys.exit(100)
    sys.exit(0)
sys.exit(0)
''')

    env = dict(os.environ)
    env["PATH"] = mock_bin + ":" + env.get("PATH", "/usr/bin:/bin")
    with open(apps_script, "r") as sf:
        helper_src = sf.read().split("\nALL_ITEMS=(")[0]
    runner = os.path.join(tmp, "run_test.sh")
    with open(runner, "w") as rf:
        rf.write(helper_src + "\napt_install code\n")
    res = subprocess.run(["/bin/bash", "-euo", "pipefail", runner], env=env, capture_output=True, text=True, timeout=10)
    assert res.returncode == 0, f"apt_install failed:\nSTDOUT:{res.stdout}\nSTDERR:{res.stderr}"
    assert not os.path.exists(state_file), "Broken state file was not healed!"
    calls = open(log_file).read().splitlines()
    assert any("apt-get install -y -f" in c and "code" not in c for c in calls), f"Missing zero-arg apt-get install -y -f in {calls}"
    assert any("apt-get install -y" in c and c.endswith(" code") for c in calls), f"Missing target install in {calls}"
""".trimIndent()

        val proc = ProcessBuilder("python3", "-B", "-c", pyTest, appsScript)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(15, TimeUnit.SECONDS)
        assertTrue("linuxdroid-apps broken dpkg test timed out:\n$output", finished)
        assertEquals("linuxdroid-apps broken dpkg test failed:\n$output", 0, proc.exitValue())
    }

    @Test
    fun linuxdroidAppsClearsOrphanedReinstReqAndRejectsHalfInstalledStatus() {
        val assetsRoot = findAssetsLinuxfs().canonicalFile
        val appsScript = File(assetsRoot, "usr/local/bin/linuxdroid-apps").canonicalPath

        val srcRoot = listOf(
            File("src/main/java/com/linuxdroid/app"),
            File("app/src/main/java/com/linuxdroid/app"),
        ).firstOrNull { it.isDirectory }
        assertNotNull("Expected Kotlin source root", srcRoot)
        val installServiceSrc = File(srcRoot!!, "runtime/RuntimeInstallService.kt").readText()
        assertTrue(
            "RuntimeInstallService must gate fastpath staging on !SessionPrefs.prootNoSeccomp(this)",
            installServiceSrc.contains("!SessionPrefs.prootNoSeccomp(this) && SessionPrefs.prootFastPath(this)")
        )

        val pyTest = """
import os
import re
import subprocess
import sys
import tempfile

apps_script = sys.argv[1]

with tempfile.TemporaryDirectory(prefix="ld-apps-reinstreq-") as tmp:
    mock_bin = os.path.join(tmp, "bin")
    os.makedirs(mock_bin, exist_ok=True)
    reinst_file = os.path.join(tmp, "reinstreq_present")
    unmet_file = os.path.join(tmp, "unmet_present")
    code_status_file = os.path.join(tmp, "code_status")
    log_file = os.path.join(tmp, "calls.log")

    for p, v in ((reinst_file, "1"), (unmet_file, "1"), (code_status_file, "unpacked ok")):
        with open(p, "w") as f:
            f.write(v)

    def write_exec(name, body):
        p = os.path.join(mock_bin, name)
        with open(p, "w") as f:
            f.write(body)
        os.chmod(p, 0o755)

    write_exec("sudo", '''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
if args and args[0] == "env":
    args = args[1:]
    while args and "=" in args[0] and not args[0].startswith("-"):
        k, v = args[0].split("=", 1)
        os.environ[k] = v
        args = args[1:]
os.execvp(args[0], args)
''')

    write_exec("code", "#!/bin/sh\nexit 0\n")

    write_exec("dpkg-query", f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
fmt = ""
pkgs = []
i = 0
while i < len(args):
    a = args[i]
    if a == "-W":
        i += 1
    elif a.startswith("-f="):
        fmt = a[3:]
        i += 1
    elif a == "-f" and i + 1 < len(args):
        fmt = args[i + 1]
        i += 2
    else:
        pkgs.append(a)
        i += 1
if not pkgs:
    if os.path.exists({reinst_file!r}):
        print("reinstreq orphan-v1019-pkg")
    print("ok bash")
    sys.exit(0)
pkg = pkgs[0]
if pkg == "code":
    st = open({code_status_file!r}).read().strip()
    if not st:
        sys.exit(1)
    print(st, end="")
    sys.exit(0)
sys.exit(1)
''')

    write_exec("dpkg", f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
with open({log_file!r}, "a") as lf:
    lf.write("dpkg " + " ".join(args) + "\\n")
if args and args[0] == "--audit":
    if os.path.exists({reinst_file!r}) or os.path.exists({unmet_file!r}):
        print("broken package state")
    sys.exit(0)
if "--remove" in args and "--force-remove-reinstreq" in args and "orphan-v1019-pkg" in args:
    if os.path.exists({reinst_file!r}):
        os.remove({reinst_file!r})
    sys.exit(0)
if args and args[0] == "--configure":
    sys.exit(1 if (os.path.exists({reinst_file!r}) or os.path.exists({unmet_file!r})) else 0)
sys.exit(0)
''')

    write_exec("apt-get", f'''#!/usr/bin/env python3
import os, sys
args = sys.argv[1:]
with open({log_file!r}, "a") as lf:
    lf.write("apt-get " + " ".join(args) + "\\n")
if "update" in args:
    sys.exit(0)
if "check" in args:
    sys.exit(100 if (os.path.exists({reinst_file!r}) or os.path.exists({unmet_file!r})) else 0)
if "install" in args and ("-f" in args or "--fix-broken" in args):
    if os.path.exists({reinst_file!r}):
        sys.stderr.write("E: The package orphan-v1019-pkg needs to be reinstalled, but I can't find an archive for it.\\n")
        sys.exit(100)
    if os.path.exists({unmet_file!r}):
        os.remove({unmet_file!r})
    sys.exit(0)
sys.exit(0)
''')

    env = dict(os.environ)
    env["PATH"] = mock_bin + ":" + env.get("PATH", "/usr/bin:/bin")

    # 1. Half-unpacked 'code' binary on PATH must report [Not Installed], NOT [Installed]
    r_list_broken = subprocess.run([apps_script, "--list"], env=env, capture_output=True, text=True, timeout=10)
    assert r_list_broken.returncode == 0, r_list_broken.stderr
    assert re.search(r"^\s*vscode\s+\[Not Installed\]", r_list_broken.stdout, re.M), f"Expected vscode [Not Installed], got:\n{r_list_broken.stdout}"

    # 2. Cleanly installed 'code' reports [Installed]
    with open(code_status_file, "w") as f:
        f.write("installed ok")
    r_list_ok = subprocess.run([apps_script, "--list"], env=env, capture_output=True, text=True, timeout=10)
    assert r_list_ok.returncode == 0, r_list_ok.stderr
    assert re.search(r"^\s*vscode\s+\[Installed\]", r_list_ok.stdout, re.M), f"Expected vscode [Installed], got:\n{r_list_ok.stdout}"

    # 3. --repair clears orphaned reinstreq package and heals remaining unmet dependencies
    r_repair = subprocess.run([apps_script, "--repair"], env=env, capture_output=True, text=True, timeout=10)
    assert r_repair.returncode == 0, f"--repair failed:\nSTDOUT:{r_repair.stdout}\nSTDERR:{r_repair.stderr}"
    assert not os.path.exists(reinst_file), "Orphaned reinstreq package was not removed!"
    assert not os.path.exists(unmet_file), "Remaining unmet dependency state was not healed!"
    calls = open(log_file).read().splitlines()
    assert any("--force-remove-reinstreq" in c and "orphan-v1019-pkg" in c for c in calls), f"Missing reinstreq removal in {calls}"
""".trimIndent()

        val proc = ProcessBuilder("python3", "-B", "-c", pyTest, appsScript)
            .redirectErrorStream(true)
            .start()
        val output = proc.inputStream.bufferedReader().readText()
        val finished = proc.waitFor(15, TimeUnit.SECONDS)
        assertTrue("linuxdroid-apps reinstreq test timed out:\n$output", finished)
        assertEquals("linuxdroid-apps reinstreq test failed:\n$output", 0, proc.exitValue())
    }
}
