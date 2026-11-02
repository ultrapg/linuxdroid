#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
OUT_DIR="${1:-$ROOT_DIR/app/src/main/assets/linuxfs}"

mkdir -p "$OUT_DIR/usr/local/bin" "$OUT_DIR/usr/local/lib" "$OUT_DIR/usr/lib/chromium"

aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
  -o "$OUT_DIR/usr/local/lib/libblsession.so" "$ROOT_DIR"/tools/linuxfs/preload/*.c -ldl
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/lib/libblsession.so"

aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -pthread \
  -o "$OUT_DIR/usr/local/lib/libblfastpath.so" "$ROOT_DIR/tools/proot/fastpath/fastpath.c" -ldl
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/lib/libblfastpath.so"

aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wall -nostdlib -ffreestanding -fno-stack-protector \
  -fno-tree-loop-distribute-patterns -Wl,-z,defs \
  -o "$OUT_DIR/usr/local/lib/libblaudit.so" "$ROOT_DIR/tools/proot/fastpath/audit.c"
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/lib/libblaudit.so"

aarch64-linux-gnu-gcc -O2 -Wall -Wextra \
  -o "$OUT_DIR/usr/local/bin/linuxdroid-clipboard" "$ROOT_DIR/tools/linuxfs/clipboard/clipboard.c" -ldl
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/local/bin/linuxdroid-clipboard"

aarch64-linux-gnu-gcc -O2 -Wall -Wextra \
  -o "$OUT_DIR/usr/lib/chromium/chrome-sandbox" "$ROOT_DIR/tools/linuxfs/sandbox/chrome_sandbox.c"
aarch64-linux-gnu-strip --strip-unneeded "$OUT_DIR/usr/lib/chromium/chrome-sandbox"

echo "Built ARM64 guest preloads and sandbox helper in $OUT_DIR"
ls -lh "$OUT_DIR/usr/local/lib/libblsession.so" \
  "$OUT_DIR/usr/local/lib/libblfastpath.so" \
  "$OUT_DIR/usr/local/lib/libblaudit.so" \
  "$OUT_DIR/usr/local/bin/linuxdroid-clipboard" \
  "$OUT_DIR/usr/lib/chromium/chrome-sandbox"
