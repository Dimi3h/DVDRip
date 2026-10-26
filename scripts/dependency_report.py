#!/usr/bin/env python3
"""Prints, for every library and plugin in gradle/libs.versions.toml, the pinned version and the
newest stable release. Informational only: it always exits 0, so it never breaks a build.

Usage: python3 scripts/dependency_report.py
"""
import pathlib
import re
import sys
import tomllib
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

GOOGLE = "https://dl.google.com/dl/android/maven2"
CENTRAL = "https://repo.maven.apache.org/maven2"
PORTAL = "https://plugins.gradle.org/m2"
UNSTABLE = re.compile(r"(alpha|beta|rc|dev|eap|snapshot|preview|-m\d|milestone)", re.IGNORECASE)

# Plugin ids that are not published as Gradle plugin markers where we would look for them.
PLUGIN_ARTIFACTS = {
    "com.android.application": (GOOGLE, "com.android.tools.build", "gradle"),
    "com.android.library": (GOOGLE, "com.android.tools.build", "gradle"),
}


def repository_for(group: str) -> str:
    if group.startswith(("androidx.", "com.android.", "com.google.android.")):
        return GOOGLE
    return CENTRAL


def version_key(version: str):
    parts = re.split(r"[.\-]", version)
    return [(0, int(p)) if p.isdigit() else (1, p) for p in parts]


def newest_stable(base: str, group: str, artifact: str) -> str:
    url = f"{base}/{group.replace('.', '/')}/{artifact}/maven-metadata.xml"
    try:
        with urllib.request.urlopen(url, timeout=20) as response:
            root = ET.parse(response).getroot()
    except (urllib.error.URLError, ET.ParseError, TimeoutError) as error:
        return f"unavailable ({error.__class__.__name__})"
    versions = [v.text for v in root.iter("version") if v.text and not UNSTABLE.search(v.text)]
    return max(versions, key=version_key) if versions else "no stable release"


def main() -> int:
    catalog_path = pathlib.Path(__file__).resolve().parent.parent / "gradle" / "libs.versions.toml"
    catalog = tomllib.loads(catalog_path.read_text(encoding="utf-8"))
    versions = catalog.get("versions", {})

    def pinned(entry: dict) -> str | None:
        version = entry.get("version")
        if isinstance(version, dict):
            return versions.get(version.get("ref"))
        if isinstance(version, str):
            return version
        return None

    rows = []
    for name, entry in catalog.get("libraries", {}).items():
        current = pinned(entry)
        if current is None:
            continue  # managed by a BOM
        group, artifact = entry["module"].split(":")
        rows.append((name, current, newest_stable(repository_for(group), group, artifact)))

    for name, entry in catalog.get("plugins", {}).items():
        plugin_id = entry["id"]
        current = pinned(entry)
        if plugin_id in PLUGIN_ARTIFACTS:
            base, group, artifact = PLUGIN_ARTIFACTS[plugin_id]
        elif plugin_id.startswith("org.jetbrains.kotlin."):
            base, group, artifact = CENTRAL, "org.jetbrains.kotlin", "kotlin-gradle-plugin"
        else:
            base, group, artifact = PORTAL, plugin_id, f"{plugin_id}.gradle.plugin"
        rows.append((f"plugin {name}", current, newest_stable(base, group, artifact)))

    width = max(len(r[0]) for r in rows)
    print(f"{'entry'.ljust(width)}  {'pinned':<16}  newest stable")
    for name, current, latest in sorted(rows):
        marker = "" if latest == current or latest.startswith(("unavailable", "no stable")) else "  <- update available"
        print(f"{name.ljust(width)}  {current:<16}  {latest}{marker}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
