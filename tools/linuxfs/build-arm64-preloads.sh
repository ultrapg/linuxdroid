#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/app/src/main/assets/linuxfs}"
COMPAT_HDR="$ROOT_DIR/tools/linuxfs/preload/compat_glibc.h"

mkdir -p "$OUT_DIR/usr/local/bin" "$OUT_DIR/usr/local/lib"

aarch64-linux-gnu-gcc -shared -fPIC -O2 -std=gnu11 -Wall -pthread \
  -include "$COMPAT_HDR" \
  -o "$OUT_DIR/usr/local/lib/libblsession.so" "$ROOT_DIR"/tools/linuxfs/preload/*.c -ldl
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/lib/libblsession.so"

aarch64-linux-gnu-gcc -shared -fPIC -O2 -std=gnu11 -march=armv8-a+nosve -fno-tree-vectorize -Wall -pthread \
  -include "$COMPAT_HDR" \
  -o "$OUT_DIR/usr/local/lib/libblfastpath.so" "$ROOT_DIR/tools/proot/fastpath/fastpath.c" -ldl
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/lib/libblfastpath.so"

for so in "$OUT_DIR/usr/local/lib/libblsession.so" "$OUT_DIR/usr/local/lib/libblfastpath.so"; do
  if aarch64-linux-gnu-readelf -V "$so" | grep -qE 'GLIBC_2\.(3[89]|[4-9][0-9])'; then
    echo "ERROR: $so imports too-new GLIBC symbol version (>= 2.38):" >&2
    aarch64-linux-gnu-readelf --dyn-syms -W "$so" | grep -E 'GLIBC_2\.(3[89]|[4-9][0-9])' >&2 || true
    exit 1
  fi
done

aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -nostdlib -ffreestanding -fno-stack-protector \
  -fno-tree-loop-distribute-patterns -Wl,-z,defs \
  -o "$OUT_DIR/usr/local/lib/libblaudit.so" "$ROOT_DIR/tools/proot/fastpath/audit.c"
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/lib/libblaudit.so"

aarch64-linux-gnu-gcc -O2 -std=gnu11 -Wall -Wextra \
  -include "$COMPAT_HDR" \
  -o "$OUT_DIR/usr/local/bin/linuxdroid-clipboard" "$ROOT_DIR/tools/linuxfs/clipboard/clipboard.c" -ldl
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/bin/linuxdroid-clipboard"

echo "Built ARM64 guest preloads in $OUT_DIR"
ls -lh "$OUT_DIR/usr/local/lib/libblsession.so" \
  "$OUT_DIR/usr/local/lib/libblfastpath.so" \
  "$OUT_DIR/usr/local/lib/libblaudit.so" \
  "$OUT_DIR/usr/local/bin/linuxdroid-clipboard"
