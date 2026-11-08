#!/bin/bash
# Builds DiscRip.app and installs it in /Applications (replacing an older version), then opens it.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
"$ROOT/scripts/build-app.sh"
if pgrep -xq DiscRip; then
  osascript -e 'tell application id "be.discrip.DiscRip" to quit' || true
  for _ in 1 2 3 4 5 6 7 8 9 10; do pgrep -xq DiscRip || break; sleep 1; done
  if pgrep -xq DiscRip; then echo "DiscRip is still running (a rip in progress?). Quit it first."; exit 1; fi
fi
rm -rf /Applications/DiscRip.app
ditto "$ROOT/dist/DiscRip.app" /Applications/DiscRip.app
echo "==> /Applications/DiscRip.app"
open /Applications/DiscRip.app
