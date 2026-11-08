#!/bin/bash
# Builds dvdauthor 0.7.2 (GPL-3.0, test tooling only, never linked into the app) so that
# scripts/make-test-iso.sh can author small DVD-Video test discs. Compiled directly with clang
# using a handwritten config.h, because the git tag has no generated configure script and
# autotools are not part of the Command Line Tools.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$ROOT/.build/dvdauthor-dl"
OUT="$ROOT/out/bin"
VER=0.7.2
SHA=fd66b92f6c67bd406030ff0f1c3233175768b534df4d341c3f380ecd61de91b5
mkdir -p "$WORK" "$OUT"
TARBALL="$WORK/dvdauthor-$VER.tar.gz"
if [ ! -f "$TARBALL" ] || ! echo "$SHA  $TARBALL" | shasum -a 256 -c - >/dev/null 2>&1; then
  curl -fSL "https://github.com/ldo/dvdauthor/archive/refs/tags/$VER.tar.gz" -o "$TARBALL"
fi
echo "$SHA  $TARBALL" | shasum -a 256 -c -
rm -rf "$WORK/dvdauthor-$VER" && tar -C "$WORK" -xzf "$TARBALL"
cd "$WORK/dvdauthor-$VER/src"

cat > config.h <<'EOF'
#define PACKAGE_NAME "DVDAuthor"
#define PACKAGE_VERSION "0.7.2"
#define PACKAGE_STRING "DVDAuthor 0.7.2"
#define PACKAGE_BUGREPORT "dvdauthor-users@lists.sourceforge.net"
#define HAVE_STDBOOL_H 1
#define HAVE__BOOL 1
#define HAVE_STDLIB_H 1
#define HAVE_STDINT_H 1
#define HAVE_STRINGS_H 1
#define HAVE_STRING_H 1
#define HAVE_UNISTD_H 1
#define HAVE_INTTYPES_H 1
#define HAVE_MEMORY_H 1
#define HAVE_SYS_STAT_H 1
#define HAVE_SYS_TYPES_H 1
#define HAVE_GETOPT_H 1
#define HAVE_GETOPT_LONG 1
#define HAVE_STRNDUP 1
#define HAVE_DECL_O_BINARY 0
#define DEFAULT_VIDEO_FORMAT 2
#define LOCALIZE_FILENAMES 0
EOF

flex -s -B -Cem -odvdvml.c -Pdvdvm dvdvml.l
bison -o dvdvmy.c -d -p dvdvm dvdvmy.y
clang -O1 -w -std=gnu89 -Wno-implicit-function-declaration -Wno-int-conversion -I. $(xml2-config --cflags) -DSYSCONFDIR='"/etc"' \
  dvdauthor.c dvdcompile.c dvdvml.c dvdvmy.c dvdifo.c dvdvob.c dvdpgc.c dvdcli.c readxml.c conffile.c compat.c \
  $(xml2-config --libs) -o "$OUT/dvdauthor"
echo "==> $OUT/dvdauthor"
