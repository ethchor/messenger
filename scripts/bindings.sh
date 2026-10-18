#!/usr/bin/env bash
# Regenerates the Swift and Kotlin bindings of crates/ffi.
#
# The bindings are committed, so Xcode and Android Studio can build without
# running this first. Run it after changing the FFI API; CI fails if the
# committed files are out of date (scripts/bindings.sh --check).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SWIFT_OUT="$ROOT/apple/Messenger/Core/Generated"
KOTLIN_OUT="$ROOT/android/core/src/main/java/com/qwantarc/messenger/core"
CHECK=0
[ "${1:-}" = "--check" ] && CHECK=1

export PATH="$HOME/.cargo/bin:$PATH"
cargo build --quiet --manifest-path "$ROOT/Cargo.toml" -p messenger-ffi --lib
case "$(uname -s)" in
  Darwin) LIB="$ROOT/target/debug/libmessenger_ffi.dylib" ;;
  *) LIB="$ROOT/target/debug/libmessenger_ffi.so" ;;
esac

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
bindgen() {
  cargo run --quiet --manifest-path "$ROOT/Cargo.toml" -p messenger-ffi --features bindgen --bin uniffi-bindgen -- \
    generate --library "$LIB" --no-format "$@"
}
bindgen --language swift --out-dir "$TMP/swift"
bindgen --language kotlin --out-dir "$TMP/kotlin"

# The app target reaches the C header through its bridging header, so the
# module map is not used.
pairs=(
  "$TMP/swift/messenger_ffi.swift:$SWIFT_OUT/messenger_ffi.swift"
  "$TMP/swift/messenger_ffiFFI.h:$SWIFT_OUT/messenger_ffiFFI.h"
  "$TMP/kotlin/com/qwantarc/messenger/core/messenger_ffi.kt:$KOTLIN_OUT/messenger_ffi.kt"
)
stale=0
for pair in "${pairs[@]}"; do
  src="${pair%%:*}"
  dst="${pair#*:}"
  if ! cmp -s "$src" "$dst"; then
    stale=1
    if [ "$CHECK" = 1 ]; then
      echo "out of date: ${dst#"$ROOT"/}" >&2
    else
      mkdir -p "$(dirname "$dst")"
      cp "$src" "$dst"
      echo "updated ${dst#"$ROOT"/}"
    fi
  fi
done
if [ "$CHECK" = 1 ] && [ "$stale" = 1 ]; then
  echo "Run scripts/bindings.sh and commit the result." >&2
  exit 1
fi
[ "$stale" = 0 ] && echo "bindings are up to date"
exit 0
