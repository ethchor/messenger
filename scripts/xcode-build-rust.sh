#!/bin/bash
# Run by the "Build Rust core" phase of the Xcode app target. Builds
# libmessenger_ffi.a for the platform and architectures Xcode is building and
# puts it where the linker looks ($TARGET_TEMP_DIR/rust). Also refreshes the
# Swift bindings when the FFI crate changed.
#
# Needs Rust from https://rustup.rs. Missing iOS/macOS targets are added with
# rustup on first build.
set -euo pipefail

REPO="$(cd "${SRCROOT:?run from Xcode}/.." && pwd)"
# Xcode gives build phases a short PATH. Look where Rust usually is (rustup's
# own install, Homebrew, and Homebrew's unlinked rustup), then the system
# folders, whose cc finds the SDK by itself, and only then the PATH we were
# given: CI runners have Rust somewhere on it, but its cc is a bare clang.
export PATH="$HOME/.cargo/bin:/opt/homebrew/opt/rustup/bin:/opt/homebrew/bin:/usr/local/opt/rustup/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin${PATH:+:$PATH}"

if ! command -v cargo >/dev/null 2>&1 || ! command -v rustup >/dev/null 2>&1; then
  echo "error: Rust is not installed. Install it from https://rustup.rs, then build again."
  exit 1
fi

case "${CONFIGURATION:-Debug}" in
  Release) PROFILE=release; CARGO_PROFILE_FLAG=(--release) ;;
  *) PROFILE=debug; CARGO_PROFILE_FLAG=() ;;
esac

TARGETS=()
for ARCH in ${ARCHS:?}; do
  case "${PLATFORM_NAME:?}:$ARCH" in
    iphoneos:arm64) TARGETS+=(aarch64-apple-ios) ;;
    iphonesimulator:arm64) TARGETS+=(aarch64-apple-ios-sim) ;;
    iphonesimulator:x86_64) TARGETS+=(x86_64-apple-ios) ;;
    macosx:arm64) TARGETS+=(aarch64-apple-darwin) ;;
    macosx:x86_64) TARGETS+=(x86_64-apple-darwin) ;;
    *)
      echo "error: no Rust target for $PLATFORM_NAME/$ARCH"
      exit 1
      ;;
  esac
done

INSTALLED="$(rustup target list --installed)"
for TARGET in "${TARGETS[@]}"; do
  if ! grep -qx "$TARGET" <<<"$INSTALLED"; then
    echo "note: adding Rust target $TARGET"
    rustup target add "$TARGET"
  fi
done

# Build in a clean environment. Xcode exports SDKROOT and friends for the
# platform being built; Cargo build scripts compiled for the Mac itself would
# pick them up and fail to link.
CLEAN_ENV=(HOME="$HOME" PATH="$PATH" USER="${USER:-}" TMPDIR="${TMPDIR:-/tmp}")
[ -n "${DEVELOPER_DIR:-}" ] && CLEAN_ENV+=(DEVELOPER_DIR="$DEVELOPER_DIR")
[ -n "${IPHONEOS_DEPLOYMENT_TARGET:-}" ] && CLEAN_ENV+=(IPHONEOS_DEPLOYMENT_TARGET="$IPHONEOS_DEPLOYMENT_TARGET")
[ -n "${MACOSX_DEPLOYMENT_TARGET:-}" ] && CLEAN_ENV+=(MACOSX_DEPLOYMENT_TARGET="$MACOSX_DEPLOYMENT_TARGET")

LIBS=()
for TARGET in "${TARGETS[@]}"; do
  env -i "${CLEAN_ENV[@]}" cargo build --manifest-path "$REPO/Cargo.toml" -p messenger-ffi --lib \
    --target "$TARGET" ${CARGO_PROFILE_FLAG[@]+"${CARGO_PROFILE_FLAG[@]}"}
  LIBS+=("$REPO/target/$TARGET/$PROFILE/libmessenger_ffi.a")
done

OUT="${TARGET_TEMP_DIR:?}/rust"
mkdir -p "$OUT"
if [ "${#LIBS[@]}" -eq 1 ]; then
  cp -f "${LIBS[0]}" "$OUT/libmessenger_ffi.a"
else
  lipo -create "${LIBS[@]}" -output "$OUT/libmessenger_ffi.a"
fi

# Keep the Swift bindings in step with the Rust API. They're committed, so
# this only does work after the FFI crate changed.
GENERATED="$REPO/apple/Messenger/Core/Generated/messenger_ffi.swift"
if [ -n "$(find "$REPO/crates/ffi/src" -newer "$GENERATED" -name '*.rs' 2>/dev/null | head -1)" ]; then
  echo "note: FFI crate changed; regenerating bindings"
  env -i "${CLEAN_ENV[@]}" /bin/bash "$REPO/scripts/bindings.sh"
  touch "$GENERATED"
fi
