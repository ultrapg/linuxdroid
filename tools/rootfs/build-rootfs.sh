#!/usr/bin/env bash
# Reproducible ARM64 Ubuntu 24.04 LTS Workstation Rootfs Builder for LinuxDroid.
# Builds clean, unbranded stock KDE Plasma (Wayland) and XFCE4 editions with:
#   - Fresh Mesa Vulkan & OpenGL stack (Venus, VirGL, PanVK/Panfrost, Zink, Lavapipe/LLVMpipe)
#   - Qualcomm Adreno KGSL Mesa Turnip (DD-Turnip-v0.3.4, protected via dpkg-divert)
#   - FEX-Emu (fex-emu-armv8.0) preferred for x86/x86_64 Linux binary translation
#   - Official Mozilla ARM64 .deb Firefox (WebRender GPU enabled, zero Snap) & MPV
#   - LinuxDroid guest workstation bridges (D-Bus UPower/NetworkManager, IME, clipboard, root)

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${OUT_DIR:-$REPO_ROOT/dist}"
WORK_DIR="${WORK_DIR:-/tmp/linuxdroid-rootfs-build}"
CACHE_DIR="${CACHE_DIR:-$WORK_DIR/cache}"
GITHUB_REPO="${GITHUB_REPO:-ultrapg/linuxdroid}"
RELEASE_TAG="${RELEASE_TAG:-v1.0.0}"

UBUNTU_BASE_URL="https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.5-base-arm64.tar.gz"
UBUNTU_BASE_SHA256="a91d5a93010193712d346d761372b7c9db6dfcf093893161c64ca107f05914f2"

KGSL_TURNIP_URL="https://github.com/Droid-Deck/Drivers-CI/releases/download/DD-Turnip-v0.3.4/DD-Turnip-v0.3.4.zip"
KGSL_TURNIP_SHA256="81db74cff1bf6568581cfd79203bec22f48856006aeac09e9e0a3eb3f9416fc1"

PROOT_HOST_URL="https://github.com/termux/proot/archive/4dba3afbf3a63af89b4d9c1a59bf2bda10f4d10f.tar.gz"
PROOT_HOST_SHA256="de94f648547d1b62431073b82dc99d56ef0ddcef6da022e0dfffe2bf661cc34e"
TALLOC_URL="https://distfiles.macports.org/talloc/talloc-2.4.3.tar.gz"
TALLOC_SHA256="dc46c40b9f46bb34dd97fe41f548b0e8b247b77a918576733c528e83abd854dd"

FEX_PPA_FPR="EDB98BFE8A2310DC9C4A376E76DBFEBEA206F5AC"
KISAK_PPA_FPR="EB8B81E14DA65431D7504EA8F63F0F2B90935439"
MOZILLA_APT_FPR="35BAA0B33E9EB396F59CA838C0BA5CE6DC6315A3"

log() {
  printf '\n==> [%s] %s\n' "$(date -u '+%H:%M:%S')" "$*"
}

require_cmd() {
  for cmd in "$@"; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      echo "ERROR: Required host tool '$cmd' is not installed." >&2
      exit 1
    fi
  done
}

verify_sha256() {
  local file="$1"
  local expected="$2"
  local actual
  actual="$(sha256sum "$file" | awk '{print $1}')"
  if [ "$actual" != "$expected" ]; then
    echo "ERROR: SHA256 mismatch for $file (expected $expected, got $actual)" >&2
    exit 1
  fi
}

fetch_key_by_fpr() {
  local fpr="$1"
  local dest_gpg="$2"
  local tmp_gnupg
  tmp_gnupg="$(mktemp -d)"
  chmod 700 "$tmp_gnupg"
  gpg --homedir "$tmp_gnupg" --batch --keyserver hkps://keyserver.ubuntu.com --recv-keys "$fpr"
  local got_fpr
  got_fpr="$(gpg --homedir "$tmp_gnupg" --batch --with-colons --fingerprint "$fpr" | awk -F: '/^fpr:/ {print $10; exit}')"
  if [ "$got_fpr" != "$fpr" ]; then
    rm -rf "$tmp_gnupg"
    echo "ERROR: GPG fingerprint mismatch (expected $fpr, got $got_fpr)" >&2
    exit 1
  fi
  sudo mkdir -p "$(dirname "$dest_gpg")"
  gpg --homedir "$tmp_gnupg" --batch --export "$fpr" | sudo tee "$dest_gpg" >/dev/null
  sudo chmod 0644 "$dest_gpg"
  rm -rf "$tmp_gnupg"
}

fetch_mozilla_key() {
  local dest_gpg="$1"
  local tmp_gnupg tmp_key
  tmp_gnupg="$(mktemp -d)"
  tmp_key="$tmp_gnupg/mozilla.key"
  chmod 700 "$tmp_gnupg"
  curl -fsSL "https://packages.mozilla.org/apt/repo-signing-key.gpg" -o "$tmp_key"
  gpg --homedir "$tmp_gnupg" --batch --import "$tmp_key"
  local got_fpr
  got_fpr="$(gpg --homedir "$tmp_gnupg" --batch --with-colons --fingerprint "$MOZILLA_APT_FPR" | awk -F: '/^fpr:/ {print $10; exit}')"
  if [ "$got_fpr" != "$MOZILLA_APT_FPR" ]; then
    rm -rf "$tmp_gnupg"
    echo "ERROR: Mozilla APT GPG fingerprint mismatch (expected $MOZILLA_APT_FPR, got $got_fpr)" >&2
    exit 1
  fi
  sudo mkdir -p "$(dirname "$dest_gpg")"
  gpg --homedir "$tmp_gnupg" --batch --export "$MOZILLA_APT_FPR" | sudo tee "$dest_gpg" >/dev/null
  sudo chmod 0644 "$dest_gpg"
  rm -rf "$tmp_gnupg"
}

ensure_host_proot() {
  local out_bin="$CACHE_DIR/proot-host"
  if [ -x "$out_bin" ]; then
    return 0
  fi
  log "Building patched host PRoot (Linux statx + AT_EMPTY_PATH + openat2 support)..."
  local tmp_build proot_tools
  tmp_build="$(mktemp -d)"
  proot_tools="$REPO_ROOT/tools/proot"
  curl -fsSL --retry 3 "$PROOT_HOST_URL" -o "$tmp_build/proot.tar.gz"
  verify_sha256 "$tmp_build/proot.tar.gz" "$PROOT_HOST_SHA256"
  curl -fsSL --retry 3 "$TALLOC_URL" -o "$tmp_build/talloc.tar.gz"
  verify_sha256 "$tmp_build/talloc.tar.gz" "$TALLOC_SHA256"
  mkdir -p "$tmp_build/proot" "$tmp_build/talloc/config"
  tar -xzf "$tmp_build/proot.tar.gz" -C "$tmp_build/proot" --strip-components=1
  tar -xzf "$tmp_build/talloc.tar.gz" -C "$tmp_build/talloc" --strip-components=1
  cp "$proot_tools/statx_mnt_id.h" "$tmp_build/proot/src/tracee/statx_mnt_id.h"
  cp "$proot_tools/profile.h" "$tmp_build/proot/src/syscall/profile.h"
  for patch_file in "$proot_tools"/patches/*.patch; do
    patch -d "$tmp_build/proot" -p1 --forward --quiet < "$patch_file"
  done
  cp "$proot_tools/talloc-config.h" "$tmp_build/talloc/config/config.h"
  gcc -c -O2 -include limits.h -D__STDC_WANT_LIB_EXT1__=1 -DHAVE_CONFIG_H \
    -I"$tmp_build/talloc/config" -I"$tmp_build/talloc" -I"$tmp_build/talloc/lib/replace" \
    -o "$tmp_build/talloc.o" "$tmp_build/talloc/talloc.c"
  ar rcs "$tmp_build/libtalloc.a" "$tmp_build/talloc.o"
  make -C "$tmp_build/proot/src" -j"$(getconf _NPROCESSORS_ONLN)" CC=gcc \
    CPPFLAGS="-D_FILE_OFFSET_BITS=64 -D_GNU_SOURCE -I. -I$tmp_build/talloc -I$proot_tools/include -DARG_MAX=131072 -Wno-error=implicit-function-declaration" \
    LDFLAGS="$tmp_build/libtalloc.a -Wl,-z,noexecstack" \
    proot >/dev/null
  install -m755 "$tmp_build/proot/src/proot" "$out_bin"
  rm -rf "$tmp_build"
}

run_in_rootfs() {
  local root="$1"
  shift
  sudo "$CACHE_DIR/proot-host" \
    -q /usr/bin/qemu-aarch64-static \
    -r "$root" \
    -b /dev \
    -b /proc \
    -b /sys \
    -b /dev/urandom:/dev/random \
    -w /root \
    /usr/bin/env -i \
    HOME=/root \
    USER=root \
    LOGNAME=root \
    PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    DEBIAN_FRONTEND=noninteractive \
    DEBCONF_NONINTERACTIVE_SEEN=true \
    LANG=C.UTF-8 \
    LC_ALL=C.UTF-8 \
    "$@"
}

stage_linuxfs_overlay() {
  local root="$1"
  local edition_label="$2"
  local overlay_dir="$REPO_ROOT/app/src/main/assets/linuxfs"
  local manifest="$overlay_dir/MANIFEST.txt"

  while IFS= read -r raw_line || [ -n "$raw_line" ]; do
    local line
    line="$(printf '%s' "$raw_line" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')"
    [ -z "$line" ] && continue
    case "$line" in \#*) continue ;; esac
    local mode="${line%% *}"
    local rel="${line#* }"
    sudo mkdir -p "$root/$(dirname "$rel")"
    sudo cp -f "$overlay_dir/$rel" "$root/$rel"
    sudo chmod "$mode" "$root/$rel"
  done < "$manifest"

  sudo mkdir -p "$root/etc/linuxdroid/empty" "$root/mnt/linuxdroid" "$root/run/linuxdroid"
  printf '%s\n' "$edition_label" | sudo tee "$root/etc/linuxdroid/edition" >/dev/null
  sudo chmod 0644 "$root/etc/linuxdroid/edition"

  printf '/usr/local/lib/libblsession.so\n/usr/local/lib/libblfastpath.so\n' \
    | sudo tee "$root/etc/ld.so.preload" >/dev/null
  sudo chmod 0644 "$root/etc/ld.so.preload"
}

clean_and_pack_rootfs() {
  local root="$1"
  local archive_out="$2"

  run_in_rootfs "$root" /bin/bash -euxc '
    apt-get clean
    rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/*.deb /var/cache/apt/archives/partial/*
    rm -rf /tmp/* /var/tmp/* /var/log/*.log /var/log/apt/* /var/log/dpkg.log
    rm -f /usr/sbin/policy-rc.d
  '

  sudo rm -f "$root/etc/resolv.conf"
  printf 'nameserver 1.1.1.1\nnameserver 8.8.8.8\n' | sudo tee "$root/etc/resolv.conf" >/dev/null
  sudo chmod 0644 "$root/etc/resolv.conf"
  printf 'linuxdroid\n' | sudo tee "$root/etc/hostname" >/dev/null
  sudo chmod 0644 "$root/etc/hostname"

  log "Packing $archive_out ..."
  mkdir -p "$(dirname "$archive_out")"
  rm -f "$archive_out"
  sudo tar \
    --numeric-owner \
    --owner=0 \
    --group=0 \
    --exclude='./dev/*' \
    --exclude='./proc/*' \
    --exclude='./sys/*' \
    -C "$root" -cf - . \
    | zstd -T0 -15 -o "$archive_out"
}

smoke_test_rootfs() {
  local root="$1"
  local desktop_bin="$2"

  log "Running ARM64 smoke tests in $root ($desktop_bin)..."
  run_in_rootfs "$root" /bin/bash -euxc "
    test -x '$desktop_bin'
    test -x /usr/bin/FEXInterpreter
    test -x /usr/bin/FEXRootFSFetcher
    test -x /usr/bin/firefox
    test -x /usr/bin/mpv
    test -x /usr/bin/Xwayland
    test -x /usr/bin/vulkaninfo
    test -x /usr/bin/glxinfo
    test -f /usr/share/vulkan/icd.d/virtio_icd.aarch64.json
    test -f /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
    test -f /usr/share/vulkan/icd.d/lvp_icd.aarch64.json
    ldd /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so | (! grep -q 'not found')
    firefox --version
    python3 -c 'import gi; gi.require_version(\"Gio\", \"2.0\"); from gi.repository import Gio, GLib; print(\"Gio OK\")'
  "

  # Ensure zero DroidDeck / Steam / Vapor branding exists anywhere in /etc/skel or /usr/share/linuxdroid
  if sudo grep -rniE 'droiddeck|steamos|vapor' "$root/etc/skel" "$root/etc/linuxdroid" "$root/usr/local/bin" 2>/dev/null; then
    echo "ERROR: Unexpected legacy branding detected in $root!" >&2
    exit 1
  fi
}

main() {
  require_cmd sudo gcc ar make qemu-aarch64-static curl gpg unzip zstd tar sha256sum python3

  mkdir -p "$CACHE_DIR" "$OUT_DIR"
  ensure_host_proot
  if [ "${RESUME_EDITIONS:-0}" != "1" ]; then
  sudo rm -rf "$WORK_DIR/base" "$WORK_DIR/kde" "$WORK_DIR/xfce"
  sudo mkdir -p "$WORK_DIR/base"

  local base_tar="$CACHE_DIR/ubuntu-base-24.04.5-base-arm64.tar.gz"
  if [ ! -f "$base_tar" ]; then
    log "Downloading Ubuntu 24.04.5 ARM64 base rootfs..."
    curl -fL --retry 3 "$UBUNTU_BASE_URL" -o "$base_tar"
  fi
  verify_sha256 "$base_tar" "$UBUNTU_BASE_SHA256"

  local turnip_zip="$CACHE_DIR/DD-Turnip-v0.3.4.zip"
  if [ ! -f "$turnip_zip" ]; then
    log "Downloading Qualcomm KGSL Mesa Turnip driver..."
    curl -fL --retry 3 "$KGSL_TURNIP_URL" -o "$turnip_zip"
  fi
  verify_sha256 "$turnip_zip" "$KGSL_TURNIP_SHA256"

  log "Extracting Ubuntu 24.04.5 ARM64 base into $WORK_DIR/base..."
  sudo tar -xzpf "$base_tar" -C "$WORK_DIR/base"

  if [ -d "$CACHE_DIR/apt-debs" ]; then
    sudo mkdir -p "$WORK_DIR/base/var/cache/apt/archives"
    sudo cp -n "$CACHE_DIR/apt-debs"/*.deb "$WORK_DIR/base/var/cache/apt/archives/" 2>/dev/null || true
  fi

  sudo rm -f "$WORK_DIR/base/etc/resolv.conf"
  sudo cp /etc/resolv.conf "$WORK_DIR/base/etc/resolv.conf"

  printf '#!/bin/sh\nexit 101\n' | sudo tee "$WORK_DIR/base/usr/sbin/policy-rc.d" >/dev/null
  sudo chmod 0755 "$WORK_DIR/base/usr/sbin/policy-rc.d"

  cat <<'APTCONF' | sudo tee "$WORK_DIR/base/etc/apt/apt.conf.d/99-linuxdroid-container" >/dev/null
APT::Sandbox::User "root";
APT::Install-Recommends "false";
APT::Install-Suggests "false";
Acquire::Languages "none";
APTCONF

  cat <<'APTPIN' | sudo tee "$WORK_DIR/base/etc/apt/preferences.d/99-linuxdroid-no-bloat" >/dev/null
Package: snapd
Pin: release *
Pin-Priority: -1

Package: linux-image-* linux-headers-* linux-modules-* initramfs-tools* grub-* plymouth*
Pin: release *
Pin-Priority: -1

Package: apport* whoopsie* popularity-contest ubuntu-report friendly-recovery
Pin: release *
Pin-Priority: -1

Package: akonadi-* kdepim-* kde-games* kmail* korganizer* kaddressbook*
Pin: release *
Pin-Priority: -1

Package: firefox*
Pin: origin packages.mozilla.org
Pin-Priority: 1000
APTPIN

  log "Installing verified GPG keys for FEX-Emu PPA, Kisak Fresh Mesa PPA, and Mozilla Official APT..."
  fetch_key_by_fpr "$FEX_PPA_FPR" "$WORK_DIR/base/usr/share/keyrings/fex-emu-archive-keyring.gpg"
  fetch_key_by_fpr "$KISAK_PPA_FPR" "$WORK_DIR/base/usr/share/keyrings/kisak-mesa-archive-keyring.gpg"
  fetch_mozilla_key "$WORK_DIR/base/usr/share/keyrings/packages.mozilla.org.gpg"

  log "Bootstrapping HTTPS certificates & APT repositories in base ARM64 rootfs..."
  run_in_rootfs "$WORK_DIR/base" /bin/bash -euxc '
    apt-get update
    apt-get install -y ca-certificates gpgv gnupg
  '

  cat <<'FEXLIST' | sudo tee "$WORK_DIR/base/etc/apt/sources.list.d/fex-emu.list" >/dev/null
deb [arch=arm64 signed-by=/usr/share/keyrings/fex-emu-archive-keyring.gpg] https://ppa.launchpadcontent.net/fex-emu/fex/ubuntu noble main
FEXLIST

  cat <<'MESALIST' | sudo tee "$WORK_DIR/base/etc/apt/sources.list.d/kisak-mesa.list" >/dev/null
deb [arch=arm64 signed-by=/usr/share/keyrings/kisak-mesa-archive-keyring.gpg] https://ppa.launchpadcontent.net/kisak/kisak-mesa/ubuntu noble main
MESALIST

  cat <<'MOZLIST' | sudo tee "$WORK_DIR/base/etc/apt/sources.list.d/mozilla.list" >/dev/null
deb [arch=arm64 signed-by=/usr/share/keyrings/packages.mozilla.org.gpg] https://packages.mozilla.org/apt mozilla main
MOZLIST

  log "Upgrading Ubuntu 24.04 base & installing shared GPU, FEX-Emu, Firefox, MPV & workstation stack..."
  run_in_rootfs "$WORK_DIR/base" /bin/bash -euxc '
    apt-get update
    apt-get full-upgrade -y

    apt-get install -y \
      locales tzdata sudo procps psmisc iproute2 iputils-ping openssh-client \
      curl wget git htop nano less file unzip zip xz-utils zstd bzip2 jq \
      squashfs-tools erofs-utils \
      xdg-utils shared-mime-info desktop-file-utils fontconfig \
      python3 python3-gi gir1.2-glib-2.0 gir1.2-atspi-2.0 \
      dbus dbus-x11 dbus-user-session \
      xwayland x11-xserver-utils x11-utils libx11-6 libxtst6 wl-clipboard xclip \
      mesa-vulkan-drivers libgl1-mesa-dri libglx-mesa0 libegl-mesa0 \
      libgl1 libegl1 libgles2 libgbm1 libvulkan1 \
      libdrm2 libdrm-amdgpu1 libdrm-freedreno1 \
      libxcb-dri3-0 libxcb-present0 libxcb-randr0 libxcb-shm0 libxcb-sync1 libxcb-xfixes0 \
      libxshmfence1 libwayland-client0 \
      mesa-utils vulkan-tools \
      pulseaudio-utils libpulse0 pavucontrol alsa-utils \
      firefox mpv \
      fex-emu-armv8.0 \
      fonts-noto-core fonts-noto-color-emoji fonts-dejavu-core fonts-liberation

    locale-gen en_US.UTF-8 C.UTF-8 || true
    update-locale LANG=en_US.UTF-8 || true

    if [ -x /usr/bin/FEX ] && [ ! -e /usr/bin/FEXInterpreter ]; then
      ln -sfn FEX /usr/bin/FEXInterpreter
      ln -sfn FEX /usr/bin/FEXLoader
    fi
    for icd in virtio freedreno panfrost lvp; do
      if [ -f "/usr/share/vulkan/icd.d/${icd}_icd.json" ] && [ ! -e "/usr/share/vulkan/icd.d/${icd}_icd.aarch64.json" ]; then
        ln -sfn "${icd}_icd.json" "/usr/share/vulkan/icd.d/${icd}_icd.aarch64.json"
      fi
    done
  '

  log "Installing Fastfetch (ARM64)..."
  if run_in_rootfs "$WORK_DIR/base" /bin/bash -c 'apt-cache show fastfetch >/dev/null 2>&1'; then
    run_in_rootfs "$WORK_DIR/base" apt-get install -y fastfetch
  else
    local ff_deb="$CACHE_DIR/fastfetch-linux-aarch64.deb"
    if [ ! -f "$ff_deb" ]; then
      curl -fL --retry 3 "https://github.com/fastfetch-cli/fastfetch/releases/latest/download/fastfetch-linux-aarch64.deb" -o "$ff_deb"
    fi
    sudo cp "$ff_deb" "$WORK_DIR/base/tmp/fastfetch.deb"
    run_in_rootfs "$WORK_DIR/base" dpkg -i /tmp/fastfetch.deb
    sudo rm -f "$WORK_DIR/base/tmp/fastfetch.deb"
  fi

  log "Installing KGSL-enabled Mesa Turnip driver (protected with dpkg-divert)..."
  local turnip_tmp
  turnip_tmp="$(mktemp -d)"
  unzip -q "$turnip_zip" -d "$turnip_tmp"
  run_in_rootfs "$WORK_DIR/base" dpkg-divert --add --rename \
    --divert /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so.drm \
    /usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so
  sudo cp -f "$turnip_tmp/linux/libvulkan_freedreno.so" \
    "$WORK_DIR/base/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
  sudo chmod 0755 "$WORK_DIR/base/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so"
  rm -rf "$turnip_tmp"

  log "Cloning shared base into KDE Plasma and XFCE4 trees..."
  sudo rm -rf "$WORK_DIR/base/var/cache/apt/archives"/*.deb
  sudo cp -a "$WORK_DIR/base" "$WORK_DIR/kde"
  sudo cp -a "$WORK_DIR/base" "$WORK_DIR/xfce"
  sudo rm -rf "$WORK_DIR/base"
  fi

  if [ ! -x "$WORK_DIR/kde/usr/bin/startplasma-wayland" ]; then
    log "Installing authentic upstream KDE Plasma (Breeze Wayland) into $WORK_DIR/kde..."
    run_in_rootfs "$WORK_DIR/kde" /bin/bash -euxc '
      apt-get install -y \
        plasma-desktop plasma-workspace plasma-workspace-wayland \
        kwin-wayland kwin-x11 libkf5config-bin kde-cli-tools systemsettings \
        breeze breeze-icon-theme breeze-cursor-theme breeze-gtk-theme kde-config-gtk-style \
        qtwayland5 kio-extras \
        konsole dolphin kate okular gwenview ark kde-spectacle kdialog \
        plasma-pa plasma-nm powerdevil polkit-kde-agent-1 \
        xdg-desktop-portal xdg-desktop-portal-kde
    '
  fi
  smoke_test_rootfs "$WORK_DIR/kde" "/usr/bin/startplasma-wayland"
  stage_linuxfs_overlay "$WORK_DIR/kde" "Ubuntu 24.04 LTS — KDE Plasma (Wayland)"
  local kde_archive="$OUT_DIR/linuxdroid-ubuntu-kde-arm64.tar.zst"
  clean_and_pack_rootfs "$WORK_DIR/kde" "$kde_archive"
  sudo rm -rf "$WORK_DIR/kde"

  log "Installing authentic upstream XFCE4 Workstation into $WORK_DIR/xfce..."
  run_in_rootfs "$WORK_DIR/xfce" /bin/bash -euxc '
    apt-get install -y \
      xfce4-session xfwm4 xfce4-panel xfdesktop4 xfce4-settings xfconf xfce4-appfinder \
      xfce4-terminal thunar thunar-archive-plugin thunar-volman gvfs gvfs-backends tumbler \
      mousepad atril ristretto engrampa xfce4-screenshooter xfce4-taskmanager \
      xfce4-pulseaudio-plugin xfce4-power-manager xfce4-notifyd xfce4-whiskermenu-plugin \
      zenity adwaita-icon-theme elementary-xfce-icon-theme greybird-gtk-theme \
      xdg-user-dirs xdg-user-dirs-gtk policykit-1-gnome
  '
  smoke_test_rootfs "$WORK_DIR/xfce" "/usr/bin/startxfce4"
  stage_linuxfs_overlay "$WORK_DIR/xfce" "Ubuntu 24.04 LTS — XFCE4 Workstation"
  local xfce_archive="$OUT_DIR/linuxdroid-ubuntu-xfce-arm64.tar.zst"
  clean_and_pack_rootfs "$WORK_DIR/xfce" "$xfce_archive"
  sudo rm -rf "$WORK_DIR/xfce"

  log "Generating $OUT_DIR/catalog.json..."
  local kde_sha xfce_sha kde_size xfce_size release_base
  kde_sha="$(sha256sum "$kde_archive" | awk '{print $1}')"
  xfce_sha="$(sha256sum "$xfce_archive" | awk '{print $1}')"
  kde_size="$(stat -c %s "$kde_archive")"
  xfce_size="$(stat -c %s "$xfce_archive")"
  release_base="https://github.com/$GITHUB_REPO/releases/download/$RELEASE_TAG"

  python3 - "$OUT_DIR/catalog.json" "$release_base" "$kde_sha" "$kde_size" "$xfce_sha" "$xfce_size" <<'PY'
import json
import sys

out_path, base, kde_sha, kde_size, xfce_sha, xfce_size = sys.argv[1:]
catalog = {
    "version": 1,
    "editions": [
        {
            "id": "ubuntu-kde-arm64",
            "title": "Ubuntu 24.04 LTS — KDE Plasma (Wayland)",
            "desktop": "kde",
            "description": "Full authentic KDE Plasma Breeze desktop with Konsole, Dolphin, Kate, Okular, Firefox, MPV, FEX-Emu, and hardware SoC Vulkan/OpenGL.",
            "url": f"{base}/linuxdroid-ubuntu-kde-arm64.tar.zst",
            "sha256": kde_sha,
            "size_bytes": int(kde_size),
        },
        {
            "id": "ubuntu-xfce-arm64",
            "title": "Ubuntu 24.04 LTS — XFCE4 Workstation",
            "desktop": "xfce",
            "description": "Lightweight stock XFCE4 desktop with Thunar, XFCE Terminal, Mousepad, Atril, Firefox, MPV, FEX-Emu, and hardware SoC Vulkan/OpenGL.",
            "url": f"{base}/linuxdroid-ubuntu-xfce-arm64.tar.zst",
            "sha256": xfce_sha,
            "size_bytes": int(xfce_size),
        },
    ],
}
with open(out_path, "w", encoding="utf-8") as f:
    json.dump(catalog, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY

  log "Rootfs build complete!"
  ls -lh "$kde_archive" "$xfce_archive" "$OUT_DIR/catalog.json"
}

main "$@"
