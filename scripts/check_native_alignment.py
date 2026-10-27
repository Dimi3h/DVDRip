#!/usr/bin/env python3
"""Checks that the native libraries in an APK work on devices with 16 KB memory pages.

Android 15+ devices may use 16 KB pages. A library then needs ELF LOAD segments aligned to at
least 16 KB, and, because the APK stores libraries uncompressed and maps them in place, its data
must start on a 16 KB boundary inside the zip.

Usage: check_native_alignment.py app-debug.apk
"""
import struct
import sys
import zipfile

PAGE = 16 * 1024
PT_LOAD = 1


def load_alignments(elf: bytes):
    if elf[:4] != b"\x7fELF" or elf[4] != 2:
        raise ValueError("not a 64-bit ELF file")
    endian = "<" if elf[5] == 1 else ">"
    phoff = struct.unpack_from(endian + "Q", elf, 0x20)[0]
    phentsize, phnum = struct.unpack_from(endian + "HH", elf, 0x36)
    for i in range(phnum):
        base = phoff + i * phentsize
        p_type = struct.unpack_from(endian + "I", elf, base)[0]
        if p_type == PT_LOAD:
            yield struct.unpack_from(endian + "Q", elf, base + 48)[0]


def data_offset(apk, info: zipfile.ZipInfo) -> int:
    apk.fp.seek(info.header_offset)
    header = apk.fp.read(30)
    name_length, extra_length = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_length + extra_length


def main(path: str) -> int:
    failures = []
    libraries = 0
    with zipfile.ZipFile(path) as apk:
        for info in apk.infolist():
            if not (info.filename.startswith("lib/") and info.filename.endswith(".so")):
                continue
            libraries += 1
            for align in load_alignments(apk.read(info)):
                if align < PAGE:
                    failures.append(f"{info.filename}: LOAD segment aligned to {align} bytes")
            if info.compress_type == zipfile.ZIP_STORED and data_offset(apk, info) % PAGE:
                failures.append(f"{info.filename}: not stored on a 16 KB boundary in the APK")
    for failure in failures:
        print("FAIL", failure)
    print(f"{libraries} native libraries checked, {len(failures)} problems")
    return 1 if failures or libraries == 0 else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1]))
