#!/usr/bin/env bash
# Installs the release APK on the running emulator or phone, starts it and checks that it stays up.
# The instrumented tests run the debug build, which R8 leaves alone; this catches a release build
# that R8 has broken at start-up. (scripts/check_jni_keep.py covers the callbacks of the native
# code, which only a rip reaches.)
#
#   release-smoke-test.sh app/build/outputs/apk/release/app-release.apk
set -euo pipefail

apk="${1:?usage: release-smoke-test.sh app-release.apk}"
package=be.discrip

# The release build may be signed with another key than the debug build on the device.
adb uninstall "$package" > /dev/null 2>&1 || true
adb install "$apk"
adb logcat -b crash -c
adb shell am start -W -n "$package/.MainActivity"
# Settings, the secret store and the drive search all run in the first seconds.
sleep 15

crashes="$(adb logcat -b crash -d)"
pid="$(adb shell pidof "$package" | tr -d '\r' || true)"
adb uninstall "$package" > /dev/null
if [ -n "$crashes" ] || [ -z "$pid" ]; then
    echo "The release build did not stay up${pid:+ (pid $pid)}:" >&2
    echo "${crashes:-the process is gone, with nothing in the crash log}" >&2
    exit 1
fi
echo "ok   the release build started and is running (pid $pid)"
