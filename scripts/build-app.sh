#!/bin/bash
# Builds dist/DiscRip.app (release, arm64, ad-hoc signed) from the Swift package.
# Prerequisite: native/scripts/build-native.sh has produced native/out.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
CONFIG="${1:-release}"
[ -f native/out/lib/libavformat.a ] || { echo "Run native/scripts/build-native.sh first"; exit 1; }

# The legacy build system is used because the default one fails on some setups with only the
# Command Line Tools installed (build database I/O errors).
# SCRATCH overrides the build directory (default .build).
SCRATCH="${SCRATCH:-$ROOT/.build}"
swift build -c "$CONFIG" --build-system native --scratch-path "$SCRATCH" --product DiscRip 2>&1 | grep -v "deprecated" || true
BIN="$(swift build -c "$CONFIG" --build-system native --scratch-path "$SCRATCH" --show-bin-path 2>/dev/null | tail -1)/DiscRip"
[ -x "$BIN" ] || { echo "Build failed"; exit 1; }

APP="$ROOT/dist/DiscRip.app"
rm -rf "$APP"
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources"
cp "$BIN" "$APP/Contents/MacOS/DiscRip"

# Build number: the number of commits, so every build from a newer commit is higher.
BUILD="$(git rev-list --count HEAD 2>/dev/null || echo 1)"
VERSION="1.0"
cat > "$APP/Contents/Info.plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>CFBundleName</key><string>DiscRip</string>
  <key>CFBundleDisplayName</key><string>DiscRip</string>
  <key>CFBundleIdentifier</key><string>be.discrip.DiscRip</string>
  <key>CFBundleExecutable</key><string>DiscRip</string>
  <key>CFBundlePackageType</key><string>APPL</string>
  <key>CFBundleShortVersionString</key><string>$VERSION</string>
  <key>CFBundleVersion</key><string>$BUILD</string>
  <key>CFBundleIconFile</key><string>AppIcon</string>
  <key>CFBundleDevelopmentRegion</key><string>nl</string>
  <key>CFBundleLocalizations</key><array><string>nl</string><string>en</string></array>
  <key>CFBundleAllowMixedLocalizations</key><true/>
  <key>LSMinimumSystemVersion</key><string>14.0</string>
  <key>LSApplicationCategoryType</key><string>public.app-category.video</string>
  <key>NSHighResolutionCapable</key><true/>
  <key>NSSupportsAutomaticTermination</key><false/>
  <key>NSLocalNetworkUsageDescription</key><string>DiscRip maakt verbinding met je NAS (SMB) en je Plex-server in het lokale netwerk.</string>
  <key>NSHumanReadableCopyright</key><string>Persoonlijk gebruik.</string>
</dict>
</plist>
EOF

# Localizations: Dutch is the source language, English comes from en.lproj.
cp -R Sources/DiscRip/Resources/*.lproj "$APP/Contents/Resources/"

ICONSET="$(mktemp -d)/AppIcon.iconset"
swift scripts/tools/make_icon.swift "$ICONSET"
iconutil -c icns "$ICONSET" -o "$APP/Contents/Resources/AppIcon.icns"

# Ad-hoc signature by default. Set SIGN_IDENTITY to the name of a code-signing certificate in your
# keychain (e.g. a self-signed "DiscRip Local") to keep the same identity across builds, so macOS
# stops asking again for keychain access after every update.
codesign --force --sign "${SIGN_IDENTITY:--}" --timestamp=none "$APP"
echo "==> $APP"
