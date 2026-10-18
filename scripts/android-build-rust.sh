#!/usr/bin/env bash
# Builds libmessenger_ffi.so for Android (arm64-v8a for phones, x86_64 for the
# emulator on Intel hosts) with cargo-ndk. Called by the Gradle "core" module:
#
#   scripts/android-build-rust.sh <jniLibs output dir> [debug|release]
#
# Needs Rust (https://rustup.rs) and the Android NDK (Android Studio: Settings >
# Languages & Frameworks > Android SDK > SDK Tools > NDK). Missing rustup
# targets and cargo-ndk are installed on first run.
set -euo pipefail

OUT="${1:?usage: android-build-rust.sh <output dir> [debug|release]}"
PROFILE="${2:-debug}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Android Studio doesn't inherit the shell's PATH on macOS.
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: Rust is not installed. Install it from https://rustup.rs, then build again." >&2
  exit 1
fi

if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  NDK_VERSION="$(ls -1 "$SDK/ndk" 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 || true)"
  if [ -z "$NDK_VERSION" ]; then
    echo "error: no Android NDK in $SDK/ndk. Install one from Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK (Side by side)." >&2
    exit 1
  fi
  export ANDROID_NDK_HOME="$SDK/ndk/$NDK_VERSION"
fi

TARGETS=(aarch64-linux-android x86_64-linux-android)
INSTALLED="$(rustup target list --installed)"
for TARGET in "${TARGETS[@]}"; do
  if ! grep -qx "$TARGET" <<<"$INSTALLED"; then
    echo "adding Rust target $TARGET"
    rustup target add "$TARGET"
  fi
done

if ! cargo ndk --version >/dev/null 2>&1; then
  echo "installing cargo-ndk"
  cargo install cargo-ndk --locked
fi

FLAGS=()
[ "$PROFILE" = release ] && FLAGS=(--release)
cargo ndk --target arm64-v8a --target x86_64 --output-dir "$OUT" \
  build --manifest-path "$ROOT/Cargo.toml" -p messenger-ffi --lib ${FLAGS[@]+"${FLAGS[@]}"}
