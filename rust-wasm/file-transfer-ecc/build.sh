#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)/src/main/resources/META-INF/resources/assets/vendor/file-transfer-wasm"

cd "$SCRIPT_DIR"
cargo test
cargo build --target wasm32-unknown-unknown --release

mkdir -p "$OUT_DIR"
cp "$SCRIPT_DIR/target/wasm32-unknown-unknown/release/file_transfer_ecc.wasm" "$OUT_DIR/file_transfer_ecc.wasm"
ls -lh "$OUT_DIR/file_transfer_ecc.wasm"
