#!/usr/bin/env bash
# Builds the rip engine for the build machine, plain and with AddressSanitizer and UBSan, and runs
# native/tests/test_engine.py against a freshly authored test DVD (and CSS-scrambled copies of it).
# Also checks that the Kotlin port of the CSS cipher in core is tested against what libdvdcss
# computes. No phone needed.
#
#   test-engine.sh
#
# Needs a C compiler, CMake 3.22 or newer, Ninja, nasm (for FFmpeg), Python 3, and for the test DVD
# ffmpeg, dvdauthor and genisoimage (Debian/Ubuntu: apt install ffmpeg dvdauthor genisoimage
# ninja-build nasm).
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
native="$root/native"
cmake="${CMAKE:-cmake}"
sanitize="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=undefined"

"$native/scripts/fetch-sources.sh"
"$native/scripts/build-ffmpeg.sh" host

for build in host host-asan; do
    if [ "$build" = host ]; then
        flags=(-DCMAKE_BUILD_TYPE=RelWithDebInfo)
    else
        flags=(-DCMAKE_BUILD_TYPE=Debug "-DCMAKE_C_FLAGS=$sanitize" "-DCMAKE_EXE_LINKER_FLAGS=-fsanitize=address,undefined")
    fi
    "$cmake" -S "$root/app/src/main/cpp" -B "$native/build/$build" -G Ninja "${flags[@]}" > /dev/null
    "$cmake" --build "$native/build/$build"
done

# The vectors CssCipherTest checks the Kotlin port against must be libdvdcss's own results.
if ! "$native/build/host/css_tool" vectors | cmp -s - "$root/core/src/test/resources/css/cryptkey-vectors.txt"; then
    echo "core/src/test/resources/css/cryptkey-vectors.txt differs from what libdvdcss computes; regenerate it with css_tool vectors" >&2
    exit 1
fi

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
"$native/scripts/make-test-dvd.sh" "$work/test-dvd.iso" "$work/folder"

for build in host host-asan; do
    echo "== Engine tests ($build)"
    DISCRIP_HOST="$native/build/$build/discrip_host" CSS_TOOL="$native/build/$build/css_tool" \
        TEST_ISO="$work/test-dvd.iso" TEST_FOLDER="$work/folder" python3 "$native/tests/test_engine.py"
done
