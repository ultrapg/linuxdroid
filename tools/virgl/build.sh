#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
OUT_DIR="$REPO_ROOT/app/src/main/jniLibs/arm64-v8a"
WORK_DIR="${VIRGL_BUILD_DIR:-/tmp/linuxdroid-virgl-build}"

ANDROID_SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/home/coder/android-sdk}}"
NDK_DIR="${ANDROID_NDK_HOME:-$ANDROID_SDK/ndk/27.2.12479018}"
TOOLCHAIN="$NDK_DIR/toolchains/llvm/prebuilt/linux-x86_64"
SYSROOT="$TOOLCHAIN/sysroot"
API_LEVEL=29

EPOXY_VER="1.5.10"
VIRGL_VER="1.1.0"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/src" "$WORK_DIR/stage/lib/pkgconfig" "$OUT_DIR"

if [ -d /tmp/virgl-src/libepoxy ]; then
    cp -a /tmp/virgl-src/libepoxy "$WORK_DIR/src/libepoxy"
else
    curl -fsSL "https://github.com/anholt/libepoxy/archive/refs/tags/${EPOXY_VER}.tar.gz" | tar -xz -C "$WORK_DIR/src"
    mv "$WORK_DIR/src/libepoxy-${EPOXY_VER}" "$WORK_DIR/src/libepoxy"
fi

curl -fsSL "https://gitlab.freedesktop.org/virgl/virglrenderer/-/archive/${VIRGL_VER}/virglrenderer-${VIRGL_VER}.tar.gz" | tar -xz -C "$WORK_DIR/src"
mv "$WORK_DIR/src/virglrenderer-${VIRGL_VER}" "$WORK_DIR/src/virglrenderer"

patch -d "$WORK_DIR/src/virglrenderer" -p1 < "$SCRIPT_DIR/patches/0001-android-venus-ahb-and-egl.patch"

cat > "$WORK_DIR/stage/lib/pkgconfig/vulkan.pc" <<EOF
prefix=$SYSROOT/usr
libdir=$SYSROOT/usr/lib/aarch64-linux-android/$API_LEVEL
includedir=$SYSROOT/usr/include

Name: Vulkan-Loader
Description: Android NDK Vulkan Loader
Version: 1.3.275
Libs: -L\${libdir} -lvulkan
Cflags: -I\${includedir}
EOF

cat > "$WORK_DIR/android-arm64.ini" <<EOF
[binaries]
c = '$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang'
cpp = '$TOOLCHAIN/bin/aarch64-linux-android${API_LEVEL}-clang++'
ar = '$TOOLCHAIN/bin/llvm-ar'
strip = '$TOOLCHAIN/bin/llvm-strip'
pkg-config = 'pkg-config'

[built-in options]
c_args = ['-fPIC']
c_link_args = ['-pie', '-Wl,-z,max-page-size=16384']

[properties]
pkg_config_libdir = '$WORK_DIR/stage/lib/pkgconfig'

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'aarch64'
endian = 'little'
EOF

meson setup "$WORK_DIR/build-epoxy" "$WORK_DIR/src/libepoxy" \
    --cross-file "$WORK_DIR/android-arm64.ini" \
    --prefix "$WORK_DIR/stage" \
    --libdir lib \
    -Ddefault_library=static \
    -Degl=yes \
    -Dglx=no \
    -Dx11=false \
    -Dtests=false \
    -Ddocs=false

ninja -C "$WORK_DIR/build-epoxy" install

meson setup "$WORK_DIR/build-virgl" "$WORK_DIR/src/virglrenderer" \
    --cross-file "$WORK_DIR/android-arm64.ini" \
    --prefix "$WORK_DIR/stage" \
    --libdir lib \
    -Ddefault_library=static \
    -Dplatforms=egl \
    -Dvenus=true \
    -Drender-server-worker=thread \
    -Dcheck-gl-errors=false \
    -Dtests=false

ninja -C "$WORK_DIR/build-virgl"

cp "$WORK_DIR/build-virgl/vtest/virgl_test_server" "$OUT_DIR/libvirgl_test_server.so"
cp "$WORK_DIR/build-virgl/server/virgl_render_server" "$OUT_DIR/libvirgl_render_server.so"
"$TOOLCHAIN/bin/llvm-strip" "$OUT_DIR/libvirgl_test_server.so" "$OUT_DIR/libvirgl_render_server.so"
chmod 0755 "$OUT_DIR/libvirgl_test_server.so" "$OUT_DIR/libvirgl_render_server.so"

echo "Built Android arm64-v8a VirGL + Venus servers:"
ls -lh "$OUT_DIR/libvirgl_test_server.so" "$OUT_DIR/libvirgl_render_server.so"
"$TOOLCHAIN/bin/llvm-readelf" -d "$OUT_DIR/libvirgl_test_server.so" | grep NEEDED
"$TOOLCHAIN/bin/llvm-readelf" -d "$OUT_DIR/libvirgl_render_server.so" | grep NEEDED
