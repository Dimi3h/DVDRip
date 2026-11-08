#!/bin/bash
# Reproducible build of the native libraries DiscRip links against:
#   libdvdcss  (VideoLAN, GPL-2.0-or-later)
#   libdvdread (VideoLAN, GPL-2.0-or-later)
#   FFmpeg     (LGPL-2.1-or-later configuration, no --enable-gpl)
# Output: native/out/{include,lib,bin} as static arm64 libraries for macOS.
#
# Requirements: Xcode Command Line Tools (clang, make, python3) and network access.
# meson, ninja and pkgconf are installed into a private venv under native/.build/venv.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.build"
SRC="$WORK/src"
OUT="$ROOT/out"
JOBS="$(sysctl -n hw.ncpu)"
MACOS_MIN="14.0"

DVDCSS_VER=1.6.0
DVDCSS_SHA=7ea556c846b7bfc32d47b41cae56d1863a6b6d5f706bb162778d6f298490977c
DVDREAD_VER=7.1.1
DVDREAD_SHA=a0d47876548bec806774bbf8dbf20bb19ba139464383156b32eb8e59915b90a9
FFMPEG_VER=9.0.2
FFMPEG_SHA=8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e

mkdir -p "$SRC" "$OUT"
export CFLAGS="-arch arm64 -mmacosx-version-min=$MACOS_MIN -O2"
export LDFLAGS="-arch arm64 -mmacosx-version-min=$MACOS_MIN"
export MACOSX_DEPLOYMENT_TARGET="$MACOS_MIN"
export PKG_CONFIG_PATH="$OUT/lib/pkgconfig"

fetch() { # url sha256 file
  local url="$1" sha="$2" file="$SRC/$(basename "$1")"
  if [ ! -f "$file" ] || ! echo "$sha  $file" | shasum -a 256 -c - >/dev/null 2>&1; then
    echo "==> Downloading $url"
    curl -fSL "$url" -o "$file"
  fi
  echo "$sha  $file" | shasum -a 256 -c -
}

tools() {
  if [ ! -x "$WORK/venv/bin/meson" ]; then
    python3 -m venv "$WORK/venv"
    "$WORK/venv/bin/pip" install -q "meson==1.11.2" ninja pkgconf
  fi
  export PATH="$WORK/venv/bin:$PATH"
}

build_dvdcss() {
  fetch "https://download.videolan.org/pub/videolan/libdvdcss/$DVDCSS_VER/libdvdcss-$DVDCSS_VER.tar.xz" "$DVDCSS_SHA"
  rm -rf "$SRC/libdvdcss-$DVDCSS_VER" && tar -C "$SRC" -xf "$SRC/libdvdcss-$DVDCSS_VER.tar.xz"
  cd "$SRC/libdvdcss-$DVDCSS_VER"
  meson setup build --prefix="$OUT" --libdir=lib --default-library=static --buildtype=release
  ninja -C build install
}

build_dvdread() {
  fetch "https://download.videolan.org/pub/videolan/libdvdread/$DVDREAD_VER/libdvdread-$DVDREAD_VER.tar.xz" "$DVDREAD_SHA"
  rm -rf "$SRC/libdvdread-$DVDREAD_VER" && tar -C "$SRC" -xf "$SRC/libdvdread-$DVDREAD_VER.tar.xz"
  cd "$SRC/libdvdread-$DVDREAD_VER"
  meson setup build --prefix="$OUT" --libdir=lib --default-library=static --buildtype=release \
    -Dlibdvdcss=enabled
  ninja -C build install
}

build_ffmpeg() {
  fetch "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_VER.tar.xz" "$FFMPEG_SHA"
  rm -rf "$SRC/ffmpeg-$FFMPEG_VER" && tar -C "$SRC" -xf "$SRC/ffmpeg-$FFMPEG_VER.tar.xz"
  cd "$SRC/ffmpeg-$FFMPEG_VER"
  # App components: MPEG-PS demux, Matroska mux, DVD decoders/parsers, FLAC + VideoToolbox encoders,
  # yadif/scale/format/setsar filters. Test tooling components (ffmpeg/ffprobe CLI, lavfi sources,
  # mpeg2video/ac3 encoders, dvd muxer, matroska demuxer) are used by scripts/make-test-iso.sh and tests.
  ./configure \
    --prefix="$OUT" --arch=arm64 --target-os=darwin --cc=clang \
    --extra-cflags="$CFLAGS" --extra-ldflags="$LDFLAGS" \
    --enable-static --disable-shared --enable-pic \
    --disable-autodetect --enable-videotoolbox --enable-audiotoolbox \
    --disable-doc --disable-debug --disable-network \
    --disable-everything \
    --enable-ffmpeg --enable-ffprobe --disable-ffplay \
    --enable-avformat --enable-avcodec --enable-avfilter --enable-avutil --enable-swscale --enable-swresample --enable-avdevice \
    --enable-protocol=file,pipe \
    --enable-demuxer=mpegps,matroska,mpegvideo,ac3,wav \
    --enable-muxer=matroska,mpeg2dvd,mpeg2vob,mpeg2video,ac3,null,wav \
    --enable-decoder=mpeg2video,mpeg1video,ac3,eac3,dca,mp2,mp1,mp3,pcm_dvd,pcm_s16le,pcm_s16be,pcm_s24be,dvdsub,hevc,h264,flac,rawvideo,wrapped_avframe \
    --enable-parser=mpegvideo,ac3,dca,mpegaudio,dvdsub,hevc,h264,flac \
    --enable-encoder=flac,hevc_videotoolbox,h264_videotoolbox,mpeg2video,ac3,pcm_s16le,pcm_s16be,pcm_s24le,rawvideo,dvdsub,wrapped_avframe \
    --enable-bsf=mpeg2_metadata,hevc_mp4toannexb,h264_mp4toannexb,extract_extradata \
    --enable-filter=yadif,scale,format,setsar,setdar,null,anull,aformat,aresample,buffer,buffersink,abuffer,abuffersink,copy,testsrc,testsrc2,sine,fps,pad,drawbox,trim,atrim,split,asplit,amerge,pan,interlace,tinterlace,fieldorder \
    --enable-indev=lavfi
  make -j"$JOBS"
  make install
}

tools
case "${1:-all}" in
  dvdcss) build_dvdcss ;;
  dvdread) build_dvdread ;;
  ffmpeg) build_ffmpeg ;;
  all) build_dvdcss; build_dvdread; build_ffmpeg ;;
  *) echo "usage: $0 [all|dvdcss|dvdread|ffmpeg]"; exit 2 ;;
esac
echo "==> Native libraries installed in $OUT"
