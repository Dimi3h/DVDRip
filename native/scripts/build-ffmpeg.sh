#!/usr/bin/env bash
# Builds FFmpeg from the source fetched by fetch-sources.sh, configured as LGPL with only the
# components DiscRip uses.
#
#   build-ffmpeg.sh android   arm64-v8a shared libraries for the app (needs ANDROID_NDK_HOME)
#   build-ffmpeg.sh host      static libraries for the build machine, for the native host tests
#
# Results go to native/build/ffmpeg/<target>/{include,lib}. A stamp records what was built
# (script, FFmpeg version, NDK), so running the script again returns at once when nothing changed.
set -euo pipefail

target="${1:?usage: build-ffmpeg.sh android|host}"
native_dir="$(cd "$(dirname "$0")/.." && pwd)"
version="9.0.2"
source_dir="$native_dir/third_party/ffmpeg-$version"
android_api=29

[ -f "$source_dir/configure" ] || { echo "FFmpeg sources missing: run native/scripts/fetch-sources.sh" >&2; exit 1; }

# DVD-Video in, Matroska out. The lossless path copies MPEG-2, AC-3, DTS and MPEG audio and
# re-encodes LPCM to PCM or FLAC; the lossy presets decode MPEG-2 and filter it (deinterlace,
# scale, aspect ratio) here, encode with MediaCodec, which the engine drives itself rather than
# through FFmpeg (engine/dr_mediacodec.h), and have the aspect ratio written into the parameter
# sets of its H.264 and HEVC by the metadata bitstream filters. LGPL: no --enable-gpl, no
# --enable-nonfree.
components=(
    --disable-everything
    --disable-autodetect
    --disable-programs
    --disable-doc
    --disable-debug
    --disable-avdevice
    --disable-swresample
    --disable-network
    --enable-demuxer=mpegps,mpegvideo,matroska
    --enable-muxer=matroska
    --enable-decoder=mpeg1video,mpeg2video,ac3,dca,mp2,pcm_dvd,dvdsub,flac
    --enable-encoder=flac,pcm_s16le,pcm_s24le
    --enable-parser=mpegvideo,ac3,dca,mpegaudio,dvdsub,dvd_nav
    --enable-filter=buffer,buffersink,yadif,bwdif,scale,format,setsar,null
    --enable-protocol=file,fd
    --enable-bsf=h264_metadata,hevc_metadata
)

sha256() {
    if command -v sha256sum > /dev/null; then sha256sum | cut -d' ' -f1; else shasum -a 256 | cut -d' ' -f1; fi
}

jobs="$(getconf _NPROCESSORS_ONLN 2> /dev/null || echo 4)"

case "$target" in
    android)
        ndk="${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK (r28 or newer)}"
        case "$(uname -s)" in
            Linux) host_tag=linux-x86_64 ;;
            Darwin) host_tag=darwin-x86_64 ;;
            *) echo "Unsupported build machine $(uname -s); use Linux, macOS or WSL" >&2; exit 1 ;;
        esac
        toolchain="$ndk/toolchains/llvm/prebuilt/$host_tag"
        ndk_revision="$(sed -n 's/^Pkg.Revision *= *//p' "$ndk/source.properties")"
        flags=(
            --target-os=android
            --arch=aarch64
            --cpu=armv8-a
            --enable-cross-compile
            --cc="$toolchain/bin/aarch64-linux-android$android_api-clang"
            --cxx="$toolchain/bin/aarch64-linux-android$android_api-clang++"
            --ar="$toolchain/bin/llvm-ar"
            --nm="$toolchain/bin/llvm-nm"
            --ranlib="$toolchain/bin/llvm-ranlib"
            --strip="$toolchain/bin/llvm-strip"
            --sysroot="$toolchain/sysroot"
            --pkg-config=false
            --enable-shared
            --disable-static
            --enable-pic
            # 16 KB page alignment for Android 15+ devices with 16 KB pages.
            --extra-ldflags="-Wl,-z,max-page-size=16384"
        )
        ;;
    host)
        ndk_revision=none
        flags=(
            --disable-shared
            --enable-static
            --enable-pic
            # The build machine has no MediaCodec: the host tests run the lossy path with FFmpeg's
            # own MPEG-4 Part 2 encoder in its place.
            --enable-encoder=mpeg4
        )
        ;;
    *)
        echo "Unknown target $target" >&2
        exit 1
        ;;
esac

prefix="$native_dir/build/ffmpeg/$target"
stamp="$prefix/.discrip-build"
fingerprint="$( { cat "$0"; echo "$version $ndk_revision $target"; } | sha256)"
if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$fingerprint" ]; then
    exit 0
fi

work="$native_dir/build/ffmpeg/work-$target"
rm -rf "$work" "$prefix"
mkdir -p "$work"
cd "$work"
echo "Configuring FFmpeg $version for $target"
"$source_dir/configure" --prefix="$prefix" "${components[@]}" "${flags[@]}" > configure.log 2>&1 || {
    tail -n 40 configure.log >&2
    tail -n 40 ffbuild/config.log >&2 || true
    exit 1
}
echo "Building FFmpeg $version for $target with $jobs jobs"
make -j"$jobs" > make.log 2>&1 || { tail -n 60 make.log >&2; exit 1; }
make install > install.log 2>&1 || { tail -n 40 install.log >&2; exit 1; }
cd "$native_dir"
rm -rf "$work"
echo "$fingerprint" > "$stamp"
echo "FFmpeg $version for $target is in $prefix"
