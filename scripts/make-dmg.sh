#!/usr/bin/env bash
# Builds the Mac app in Release, for Apple silicon and Intel, and packs it in
# a disk image:
#
#   scripts/make-dmg.sh        # → build/Messenger-<version>.dmg
#
# Signing follows apple/Config/Signing.xcconfig and your
# Signing.local.xcconfig. Without a team the app is signed to run as-is, which
# works on this Mac; on another Mac, open it once from System Settings →
# Privacy & Security → Open Anyway (docs/DEPLOY.md).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/build"
DERIVED="$OUT/DerivedData"
mkdir -p "$OUT"

xcodebuild -project "$ROOT/apple/Messenger.xcodeproj" -scheme Messenger \
  -configuration Release -destination 'generic/platform=macOS' \
  -derivedDataPath "$DERIVED" -quiet build

APP="$DERIVED/Build/Products/Release/Messenger.app"
VERSION="$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "$APP/Contents/Info.plist")"
DMG="$OUT/Messenger-$VERSION.dmg"

# The window people see: the app and a shortcut to drag it onto.
STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
cp -R "$APP" "$STAGE/"
ln -s /Applications "$STAGE/Applications"

rm -f "$DMG"
hdiutil create -volname Messenger -srcfolder "$STAGE" -format UDZO -quiet "$DMG"
echo "$DMG"
