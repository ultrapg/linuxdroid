# LinuxDroid

**LinuxDroid** (`com.linuxdroid.app`) turns an ARM64 Android phone or tablet into a hardware-accelerated Linux desktop computer — without root.

It pairs a native Vulkan/AHardwareBuffer Wayland compositor on Android with clean, authentic **Ubuntu 24.04 LTS (ARM64)** workstation environments (**KDE Plasma Wayland** and **XFCE4**), SoC-native Vulkan & Desktop OpenGL acceleration across **Qualcomm Snapdragon**, **MediaTek Dimensity/Helio**, and **Samsung Exynos**, and **FEX-Emu** for seamless `x86`/`x86_64` Linux application support.

---

## Highlights

- **Pure Linux Desktop Workstation**: Authentic upstream **KDE Plasma (Breeze)** and **XFCE4** desktops with zero gaming overlays, zero custom watermarks, and zero screen chrome.
- **Native SoC Vulkan & Desktop OpenGL Across All Chipsets**:
  - **Qualcomm Snapdragon (`Adreno 6xx/7xx/8xx`)**: Direct `/dev/kgsl-3d0` Mesa **Turnip** (`Vulkan 1.3`) + **Mesa Zink** (`OpenGL 4.6`), plus `adrenotools` Turnip driver injection for the host Vulkan Wayland presenter.
  - **MediaTek Dimensity / Helio (`Mali` / `Immortalis`) & Samsung Exynos (`Xclipse` AMD RDNA / `Mali`)**: Native Android NDK `virgl_test_server` + `virgl_render_server` (`--venus --use-egl-surfaceless --use-gles`) bridged directly to Android's hardware `/system/lib64/libvulkan.so` & `/system/lib64/libEGL.so` with zero-copy `AHardwareBuffer` (`dma_buf`), exposing **SoC Hardware Vulkan 1.3 via Mesa `Venus` (`virtio_icd`)** and **Desktop OpenGL 4.3 via `Mesa VirGL` (`virpipe`)** — plus native `/dev/dri/renderD128` **PanVK / Panfrost** passthrough where kernel DRM render nodes are accessible.
  - **Unified Driver Mode**: Optional **Unified (SoC Venus Vulkan + VirGL)** mode runs the exact same Mesa driver pipeline across Snapdragon, MediaTek, and Exynos devices.
- **FEX-Emu Preferred for `x86_64` Apps**: Ships **FEX-Emu** (`fex-emu-armv8.0`: `FEXInterpreter`, `FEXLoader`, `FEXRootFSFetcher`) wired into `libblsession.so` transparent `execve` interception (`linuxdroid-open` -> `linuxdroid-fex`) with Vulkan, GL, and Wayland thunks preconfigured.
- **High-Throughput PRoot Fastpath (`libblfastpath.so`)**: Binary-patches `libc.so.6` and `ld-linux-aarch64.so.1` `svc` call sites in-process via a seccomp-exempt trampoline (`0xffff00000`), cutting path syscall latency from ~25 µs down to ~0.6 µs alongside `GLIBC_TUNABLES` `rseq` & heap-padding optimizations and Android SELinux hardlink lockfile translation (`libblsession.so`).
- **Configurable CPU Core Allocation & Full SoC GPU Control**:
  - Pin the Linux desktop & terminal sessions to any exact number of hardware CPU cores (automatically preferring high-frequency Prime/Performance cores on ARM64 `big.LITTLE` / `DynamIQ` chips) or run across all CPU cores at 100% performance, alongside full hardware SoC GPU acceleration.
- **Single-Edition Safe Installer & Rootfs Manager**:
  - Dedicated tabbed Workstation UI with fixed-size, flicker-free scrollable log and list containers, single-edition active lock for stability, and 1-tap **Delete** with confirmation dialog.
- **External Screen Only Mode (VLC Style, No DeX Required) + Samsung DeX**:
  - **External Screen Only**: Outputs the Linux desktop fullscreen directly onto any connected USB-C / HDMI monitor or Wireless Cast screen via Android's `Presentation` API — without requiring Samsung DeX — while turning the phone into a multi-touch trackpad & keyboard controller with 1-tap OLED screen dimming.
  - **Auto External & Samsung DeX**: Automatically switches to an external display when plugged in, or runs as a dynamically resizable desktop window inside Samsung DeX / Android Freeform Multi-Window.
- **Sandboxed Native ARM64 Chromium**: Ships native ARM64 **Chromium** with active filesystem chroot jailing (`chrome-sandbox` Setuid API v1 + `/var/empty` path isolation) and Android kernel Seccomp-BPF sandboxing — zero `--no-sandbox` flags or warning banners.
- **Native True-Pixel HiDPI Scaling (`100%`–`200%`)**: Full native pixel rendering via logical Wayland output scaling on KDE Plasma (`kwin_wayland --scale`) and XSettings `Gdk/WindowScalingFactor` + `Xft/DPI` on XFCE4, with live restart-and-apply from the Dashboard.
- **Complete Android ↔ Linux Workstation Integration**:
  - Non-root workstation user (`linux`, `/home/linux`) with passwordless `sudo`, `pkexec`, and `linuxdroid-root` (`RootSpawner`).
  - 1-tap floating on-screen keyboard pill plus automatic Android soft keyboard popup on both touchscreen taps and trackpad/mouse clicks into text fields (`zwp_text_input_v3`, KWin `/VirtualKeyboard` D-Bus, and X11 AT-SPI2).
  - Two-way clipboard sync, low-latency `AAudio` PulseAudio sink + optional microphone input, real battery % & charging state (`UPower`), dynamic Wi-Fi / DNS sync (`NetworkManager`), and `/home/linux/Android` storage + custom host folder mounts with Dolphin/Thunar sidebar bookmarks.
  - Persistent background Foreground Service with **Pause / Freeze** (`SIGSTOP`/`SIGCONT`, 0% CPU) and **Shut Down** controls directly from the Android notification.

---

## Installation

1. Download **`linuxdroid-release.apk`** from the [Releases](https://github.com/ultrapg/linuxdroid/releases) page and install it on an ARM64 Android 8.0+ (`minSdk 26`) device.
2. Open **LinuxDroid** and select one of the official ARM64 desktop editions to download & verify automatically:
   - **Ubuntu 24.04 LTS — KDE Plasma (Wayland)** (`linuxdroid-ubuntu-kde-arm64.tar.zst`)
   - **Ubuntu 24.04 LTS — XFCE4 Workstation** (`linuxdroid-ubuntu-xfce-arm64.tar.zst`)
   - Or tap **Import Custom Rootfs (.tar.zst / .tar.xz / .tar.gz)** to install your own ARM64 Debian, Arch Linux ARM, Fedora, or Alpine rootfs archive.
3. Tap **Start Linux Desktop**.
   - Hardware `Esc` goes directly to Linux applications (Vim, Konsole, Kate, VS Code).
   - Android **Back** (or `Ctrl + Alt + Shift + Esc` on a physical keyboard) opens the **Quick Menu** (Show Keyboard, Touch / Trackpad toggle, Terminal, Pause, Shut Down).

---

## Optional Workstation Extras (`linuxdroid-apps`)

Inside the Linux desktop, launch **LinuxDroid Workstation Apps** from the application menu or run `linuxdroid-apps` in a terminal:

```bash
linuxdroid-apps fex-rootfs   # Download Ubuntu 24.04 x86_64 RootFS for FEX-Emu
linuxdroid-apps vscode       # Install official ARM64 Visual Studio Code
linuxdroid-apps vscodium     # Install open-source ARM64 VSCodium
linuxdroid-apps libreoffice  # Install LibreOffice Workstation Suite
linuxdroid-apps gimp         # Install GIMP Image Editor
linuxdroid-apps devtools     # Install GCC/Clang, CMake, Ninja, Python & Node.js
```

---

## Building from Source

### 1. Android APK

Requires JDK 17+, Android SDK (`platforms;android-35`, `build-tools;35.0.0`), and Android NDK `27.2.12479018`.

Configure a local PKCS12 keystore outside the repository (default path `~/.local/share/linuxdroid-signing/signing.properties`, or override with `LINUXDROID_SIGNING_PROPS`):

```properties
storeFile=/path/to/linuxdroid-release.jks
storePassword=YOUR_PASSWORD
keyAlias=linuxdroid
keyPassword=YOUR_PASSWORD
```

Then run unit tests and assemble the release APK:

```bash
./gradlew testDebugUnitTest assembleRelease
```

### 2. Reproducible ARM64 Ubuntu 24.04 Desktop Rootfs Archives

On an `x86_64` or `aarch64` Linux host with `proot`, `qemu-aarch64-static`, `zstd`, `curl`, `gpg`, and `unzip`:

```bash
./tools/rootfs/build-rootfs.sh
```

Outputs `linuxdroid-ubuntu-kde-arm64.tar.zst`, `linuxdroid-ubuntu-xfce-arm64.tar.zst`, and `catalog.json` into `./dist/`.

---

## License

Licensed under the [GNU General Public License v3.0](LICENSE). Built upon open-source foundations including Wayland, Mesa 3D (`Turnip`, `Venus`, `VirGL`, `PanVK`, `Zink`), virglrenderer, PRoot, PulseAudio, FEX-Emu, Termux `terminal-view`, and DroidDeck's native Wayland compositor & guest session plumbing.
