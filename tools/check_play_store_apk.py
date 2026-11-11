#!/usr/bin/env python3
"""Check a Play Store APK for every string the module relies on, without DexKit.

Two kinds of strings are verified:

* DexKit anchors: string constants named *ANCHOR* (or *Anchors lists) in DexKitResolver.kt
  must occur in the APK's dex string pools. A missing anchor means that target will not resolve.
* UI labels: the English defaults in PlayStoreLabels (LocalizedLabels.kt) must exist as string
  resource values (read with `aapt2 dump resources`). A missing label means that optional filter
  keeps its entry visible.

This is a fast pre-check for a new Play Store release. It does not replace a full DexKit
resolution, which also checks code structure.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
RESOLVER = ROOT / "app/src/main/kotlin/eu/hxreborn/gplayadblock/discovery/DexKitResolver.kt"
LABELS = ROOT / "app/src/main/kotlin/eu/hxreborn/gplayadblock/hook/LocalizedLabels.kt"

LITERAL = re.compile(r'"((?:[^"\\]|\\.)*)"')
DECLARATION = re.compile(r"^\s*(?:private\s+)?(?:const\s+)?val\s+(\w+)\s*=", re.MULTILINE)
LABEL = re.compile(r'^\s*const val (\w+) = "((?:[^"\\]|\\.)*)"', re.MULTILINE)


def unescape(literal: str) -> str:
    return (
        literal.replace("\\n", "\n").replace('\\"', '"').replace("\\$", "$").replace("\\\\", "\\")
    )


def anchors(source: str) -> dict[str, list[str]]:
    """Anchor name -> strings. Concatenated literals of a single constant are joined."""
    found: dict[str, list[str]] = {}
    declarations = list(DECLARATION.finditer(source))
    for index, declaration in enumerate(declarations):
        name = declaration.group(1)
        if "ANCHOR" not in name.upper():
            continue
        end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
        body = source[declaration.end() : end]
        literals = [unescape(value) for value in LITERAL.findall(body)]
        if not literals:
            continue
        if "listOf(" in body:
            found[name] = literals
        else:
            found[name] = ["".join(literals)]
    return found


def labels(source: str) -> dict[str, str]:
    return {name: unescape(value) for name, value in LABEL.findall(source)}


def normalize(text: str) -> str:
    """Mirrors PlayStoreLabels.normalize."""
    return " ".join(text.lower().replace("&", " and ").split())


def dex_strings(apk: Path) -> bytes:
    with zipfile.ZipFile(apk) as archive:
        return b"\0".join(
            archive.read(name)
            for name in archive.namelist()
            if re.fullmatch(r"classes\d*\.dex", name)
        )


def resource_values(apk: Path, aapt2: str) -> set[str]:
    output = subprocess.run(
        [aapt2, "dump", "resources", str(apk)],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    ).stdout
    values = set()
    for line in output.splitlines():
        stripped = line.strip()
        if stripped.startswith('() "') and stripped.endswith('"'):
            values.add(normalize(stripped[4:-1]))
    return values


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("apk", type=Path, help="Play Store base.apk")
    parser.add_argument("--aapt2", default="aapt2", help="aapt2 executable for the label check")
    parser.add_argument("--skip-labels", action="store_true", help="only check dex anchors")
    args = parser.parse_args(argv)

    failures = 0
    pool = dex_strings(args.apk)
    for name, strings in sorted(anchors(RESOLVER.read_text(encoding="utf-8")).items()):
        missing = [value for value in strings if value.encode("utf-8") not in pool]
        status = "ok" if not missing else "MISSING " + ", ".join(repr(m) for m in missing)
        failures += bool(missing)
        print(f"anchor {name}: {status}")

    if not args.skip_labels:
        values = resource_values(args.apk, args.aapt2)
        for name, text in sorted(labels(LABELS.read_text(encoding="utf-8")).items()):
            present = normalize(text) in values
            failures += not present
            print(f"label {name} {text!r}: {'ok' if present else 'MISSING'}")

    print("all strings present" if failures == 0 else f"{failures} missing")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
