#!/usr/bin/env bash
# Builds libmessenger_ffi.so for Android (arm64-v8a for phones, x86_64 for the
# emulator on Intel hosts) with cargo-ndk. Called by the Gradle "core" module:
#
#   scripts/android-build-rust.sh <jniLibs output dir> [debug|release]
#
# ANDROID_ABIS (comma-separated) limits the ABIs, and ANDROID_PLATFORM sets the
# API level to build against (the app's minSdk). Gradle sets both.
#
# Needs Rust (https://rustup.rs) and the Android NDK (Android Studio: Settings >
# Languages & Frameworks > Android SDK > SDK Tools > NDK). Missing rustup
# targets and cargo-ndk are installed on first run.
set -euo pipefail

OUT="${1:?usage: android-build-rust.sh <output dir> [debug|release]}"
PROFILE="${2:-debug}"
PLATFORM="${ANDROID_PLATFORM:-26}"
IFS=',' read -r -a ABIS <<<"${ANDROID_ABIS:-arm64-v8a,x86_64}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
# Android Studio doesn't inherit the shell's PATH on macOS.
export PATH="$HOME/.cargo/bin:/opt/homebrew/bin:/usr/local/bin:$PATH"

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: Rust is not installed. Install it from https://rustup.rs, then build again." >&2
  exit 1
fi

# The NDK: ANDROID_NDK_HOME or ANDROID_NDK_ROOT if they point somewhere real,
# else the newest NDK installed in the SDK.
NDK=""
for CANDIDATE in "${ANDROID_NDK_HOME:-}" "${ANDROID_NDK_ROOT:-}"; do
  if [ -n "$CANDIDATE" ] && [ -f "$CANDIDATE/source.properties" ]; then
    NDK="$CANDIDATE"
    break
  fi
done
if [ -z "$NDK" ]; then
  SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Library/Android/sdk}}"
  NDK_VERSION="$(ls -1 "$SDK/ndk" 2>/dev/null | sort -t. -k1,1n -k2,2n -k3,3n | tail -1 || true)"
  if [ -z "$NDK_VERSION" ]; then
    echo "error: no Android NDK in $SDK/ndk. Install one from Android Studio: Settings > Languages & Frameworks > Android SDK > SDK Tools > NDK (Side by side)." >&2
    exit 1
  fi
  NDK="$SDK/ndk/$NDK_VERSION"
fi
export ANDROID_NDK_HOME="$NDK"
echo "using NDK $NDK"

TARGETS=()
NDK_TARGETS=()
for ABI in "${ABIS[@]}"; do
  case "$ABI" in
    arm64-v8a) TARGETS+=(aarch64-linux-android) ;;
    x86_64) TARGETS+=(x86_64-linux-android) ;;
    *)
      echo "error: unsupported ABI '$ABI' (expected arm64-v8a or x86_64)" >&2
      exit 1
      ;;
  esac
  NDK_TARGETS+=(--target "$ABI")
done
echo "building ${ABIS[*]} for API $PLATFORM"

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
if [ "$PROFILE" = release ]; then
  FLAGS=(--release)
else
  # With full debug info the library is ~130 MB, which makes every install
  # slow, and AGP can only strip it with the NDK version it pins. Drop debug
  # info at link time; symbols stay, so backtraces keep function names.
  export CARGO_PROFILE_DEV_STRIP="${CARGO_PROFILE_DEV_STRIP:-debuginfo}"
fi
cargo ndk "${NDK_TARGETS[@]}" --platform "$PLATFORM" --output-dir "$OUT" \
  build --manifest-path "$ROOT/Cargo.toml" -p messenger-ffi --lib ${FLAGS[@]+"${FLAGS[@]}"}
