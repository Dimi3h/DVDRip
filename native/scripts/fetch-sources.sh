#!/usr/bin/env bash
# Fetches the third-party sources the native build compiles into native/third_party/, each pinned
# by version and SHA-256. Mirrors are tried in order; an archive is only unpacked after its
# checksum matches. Running it again is cheap: verified sources are left alone.
set -euo pipefail

native_dir="$(cd "$(dirname "$0")/.." && pwd)"
third_party="$native_dir/third_party"
downloads="$third_party/.downloads"

# name|version|sha256|archive|mirror URLs (space separated)
#
# Checksums were taken from the projects' own release files and cross-checked: libusb against
# its GitHub, SourceForge and Debian copies; libdvdcss and libdvdread against VideoLAN's .sha256
# files and release signature (key 65F7C6B4206BD057A7EB73787180713BE58D1ADC); FFmpeg against its
# release signature (key FCF986EA15E6E293A5644F10B4322F04D67658D8).
sources=(
    "libusb|1.0.30|fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf|libusb-1.0.30.tar.bz2|https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2 https://downloads.sourceforge.net/project/libusb/libusb-1.0/libusb-1.0.30/libusb-1.0.30.tar.bz2"
    "libdvdcss|1.6.0|7ea556c846b7bfc32d47b41cae56d1863a6b6d5f706bb162778d6f298490977c|libdvdcss-1.6.0.tar.xz|https://download.videolan.org/pub/videolan/libdvdcss/1.6.0/libdvdcss-1.6.0.tar.xz https://get.videolan.org/libdvdcss/1.6.0/libdvdcss-1.6.0.tar.xz"
    "libdvdread|7.1.1|a0d47876548bec806774bbf8dbf20bb19ba139464383156b32eb8e59915b90a9|libdvdread-7.1.1.tar.xz|https://download.videolan.org/pub/videolan/libdvdread/7.1.1/libdvdread-7.1.1.tar.xz https://get.videolan.org/libdvdread/7.1.1/libdvdread-7.1.1.tar.xz"
    "ffmpeg|9.0.2|8c3850283eb25fa026482078a04051e0be17347b09ef81a0849bec15a96e002e|ffmpeg-9.0.2.tar.xz|https://ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz https://www.ffmpeg.org/releases/ffmpeg-9.0.2.tar.xz"
)

sha256() {
    if command -v sha256sum > /dev/null; then
        sha256sum "$1" | cut -d' ' -f1
    else
        shasum -a 256 "$1" | cut -d' ' -f1
    fi
}

download() {
    local archive="$1" expected="$2" urls="$3" partial="$downloads/$1.partial"
    for url in $urls; do
        echo "Downloading $url"
        if curl --fail --location --silent --show-error --retry 3 --output "$partial" "$url"; then
            local actual
            actual="$(sha256 "$partial")"
            if [ "$actual" = "$expected" ]; then
                mv "$partial" "$downloads/$archive"
                return 0
            fi
            echo "Checksum mismatch for $url: expected $expected, got $actual" >&2
        fi
        rm -f "$partial"
    done
    echo "Could not download $archive from any mirror" >&2
    return 1
}

mkdir -p "$downloads"
for entry in "${sources[@]}"; do
    IFS='|' read -r name version checksum archive urls <<< "$entry"
    target="$third_party/$name-$version"
    stamp="$target/.discrip-verified"
    if [ -f "$stamp" ] && [ "$(cat "$stamp")" = "$checksum" ]; then
        continue
    fi
    if [ ! -f "$downloads/$archive" ] || [ "$(sha256 "$downloads/$archive")" != "$checksum" ]; then
        download "$archive" "$checksum" "$urls"
    fi
    rm -rf "$target"
    tar -xf "$downloads/$archive" -C "$third_party"
    [ -d "$target" ] || { echo "$archive did not unpack to $target" >&2; exit 1; }
    echo "$checksum" > "$stamp"
    echo "Ready: $name $version"
done
