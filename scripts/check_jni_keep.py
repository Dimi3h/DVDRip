#!/usr/bin/env python3
"""Checks that a minified APK keeps every Kotlin method the native code looks up by name.

    check_jni_keep.py app/build/outputs/apk/release/app-release.apk app/src/main/cpp

The native code finds its callbacks (rip progress, block reads from the drive, file writes) with
GetMethodID, by name and signature. Nothing in Kotlin calls them, so R8 drops or renames them in
the release build unless proguard-rules.pro keeps them, and the debug build the tests run on never
notices. This looks up every GetMethodID of the C sources in the APK's dex files, read with
dexdump from the Android SDK's build tools: one of the app's callback types (be.discrip.rip.Native*)
must declare the method, and every class that implements an interface declaring it must have it
too, by that name.
"""
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

LOOKUP = re.compile(r'Get(?:Static)?MethodID\([^;"]*"([^"]+)"\s*,\s*"([^"]+)"')
CALLBACK_TYPE = re.compile(r"Lbe/discrip/rip/Native\w*;")


def lookups(source_dir):
    """(name, signature, file) for every method the C sources look up."""
    found = []
    for path in sorted(glob.glob(os.path.join(source_dir, "**", "*.c"), recursive=True)):
        with open(path, encoding="utf-8") as source:
            for name, signature in LOOKUP.findall(source.read()):
                found.append((name, signature, os.path.relpath(path, source_dir)))
    return found


def find_dexdump():
    roots = [os.environ.get("ANDROID_HOME"), os.environ.get("ANDROID_SDK_ROOT")]
    if os.path.exists("local.properties"):
        with open("local.properties", encoding="utf-8") as properties:
            for line in properties:
                if line.startswith("sdk.dir="):
                    roots.append(line.split("=", 1)[1].strip())
    for root in filter(None, roots):
        tools = sorted(glob.glob(os.path.join(root, "build-tools", "*", "dexdump")))
        if tools:
            return tools[-1]
    sys.exit("dexdump not found: set ANDROID_HOME to an Android SDK with build tools")


def classes(apk, dexdump):
    """descriptor -> {"super", "interfaces", "flags", "methods"} for every class in the APK."""
    found = {}
    with tempfile.TemporaryDirectory() as work, zipfile.ZipFile(apk) as archive:
        for entry in archive.namelist():
            if not re.fullmatch(r"classes\d*\.dex", entry):
                continue
            path = archive.extract(entry, work)
            dump = subprocess.run([dexdump, path], capture_output=True, check=True).stdout.decode("latin-1")
            for block in re.split(r"\nClass #\d+\s+-\n", dump)[1:]:
                descriptor = re.search(r"Class descriptor\s+: '([^']+)'", block).group(1)
                header = block.split("Static fields")[0]
                found[descriptor] = {
                    "super": re.search(r"Superclass\s+: '([^']+)'", block).group(1),
                    "interfaces": re.findall(r"#\d+\s+: '([^']+)'", header.split("Interfaces")[1]),
                    "flags": re.search(r"Access flags\s+: \S+ \(([^)]*)\)", block).group(1).split(),
                    "methods": set(re.findall(r"name\s+: '([^']+)'\s*\n\s*type\s+: '(\([^']*\)[^']*)'", block)),
                }
    return found


def interfaces_of(descriptor, types, seen=None):
    """Every interface a type implements, through its superclasses and superinterfaces."""
    seen = set() if seen is None else seen
    info = types.get(descriptor)
    if info is None:
        return seen
    for interface in info["interfaces"]:
        if interface not in seen:
            seen.add(interface)
            interfaces_of(interface, types, seen)
    return interfaces_of(info["super"], types, seen)


def has_method(descriptor, method, types):
    """Whether a class or one of its superclasses in the APK defines the method."""
    while descriptor in types:
        if method in types[descriptor]["methods"]:
            return True
        descriptor = types[descriptor]["super"]
    return False


def main(apk, source_dir):
    types = classes(apk, find_dexdump())
    wanted = lookups(source_dir)
    if not wanted:
        sys.exit(f"no GetMethodID in {source_dir}")
    failures = 0
    for name, signature, source in wanted:
        method = (name, signature)
        declaring = sorted(d for d, info in types.items() if CALLBACK_TYPE.fullmatch(d) and method in info["methods"])
        problems = []
        if not declaring:
            problems.append("no callback type has it")
        for descriptor, info in types.items():
            concrete = "INTERFACE" not in info["flags"] and "ABSTRACT" not in info["flags"]
            if not concrete or not descriptor.startswith(("L", "[")):
                continue
            needed = [i for i in interfaces_of(descriptor, types) if i in declaring and "INTERFACE" in types[i]["flags"]]
            if needed and not has_method(descriptor, method, types):
                problems.append(f"{descriptor} implements {needed[0]} without it")
        if problems:
            failures += 1
            print(f"FAIL {name}{signature} ({source}): " + "; ".join(problems))
        else:
            print(f"ok   {name}{signature} ({source}) in {', '.join(declaring)}")
    if failures:
        sys.exit(f"{failures} of {len(wanted)} methods the native code looks up are missing from {apk}; "
                 "keep them in app/proguard-rules.pro")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
