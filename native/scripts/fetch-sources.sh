#!/usr/bin/env bash
# Fetches the third-party sources the native build compiles into native/third_party/, each pinned
# by version and SHA-256. Mirrors are tried in order; an archive is only unpacked after its
# checksum matches. Running it again is cheap: verified sources are left alone.
set -euo pipefail

native_dir="$(cd "$(dirname "$0")/.." && pwd)"
third_party="$native_dir/third_party"
downloads="$third_party/.downloads"

# name|version|sha256|archive|mirror URLs (space separated)
sources=(
    "libusb|1.0.30|fea36f34f9156400209595e300840767ab1a385ede1dc7ee893015aea9c6dbaf|libusb-1.0.30.tar.bz2|https://github.com/libusb/libusb/releases/download/v1.0.30/libusb-1.0.30.tar.bz2 https://downloads.sourceforge.net/project/libusb/libusb-1.0/libusb-1.0.30/libusb-1.0.30.tar.bz2"
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
