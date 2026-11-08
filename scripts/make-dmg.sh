#!/bin/bash
# Builds DiscRip.app and packs it in dist/DiscRip-<version>.dmg: open the DMG and drag DiscRip to
# Programma's (Applications), like any other Mac app.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
"$ROOT/scripts/build-app.sh"
APP="$ROOT/dist/DiscRip.app"
VERSION="$(/usr/libexec/PlistBuddy -c 'Print CFBundleShortVersionString' "$APP/Contents/Info.plist")"
BUILD="$(/usr/libexec/PlistBuddy -c 'Print CFBundleVersion' "$APP/Contents/Info.plist")"
DMG="$ROOT/dist/DiscRip-$VERSION-$BUILD.dmg"

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
ditto "$APP" "$STAGE/DiscRip.app"
ln -s /Applications "$STAGE/Applications"
rm -f "$DMG"
hdiutil create -quiet -volname "DiscRip" -srcfolder "$STAGE" -fs HFS+ -format UDZO -o "$DMG"
echo "==> $DMG"
