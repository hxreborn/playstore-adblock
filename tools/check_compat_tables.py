#!/usr/bin/env python3
"""Report README compatibility tables that disagree with play-store-compatibility.yaml."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path
from typing import NamedTuple

ROW = re.compile(r"^\| (?P<name>\S+) \| (?P<code>\S+) \| (?P<status>\w+) \|$", re.M)
SEPARATOR = "---"

# One row per release, both columns wildcarded across flavours
WILDCARD = "XX"


class Row(NamedTuple):
    name: str
    code: str
    status: str


def derive(metadata: dict) -> list[Row]:
    rows: dict[str, Row] = {}
    for entry in metadata["releases"]:
        name = f"{entry['version_name'].split('-')[0]}-{WILDCARD}"
        code = f"{str(entry['version_code'])[:6]}{WILDCARD}"
        row = Row(name, code, entry["status"])
        if name in rows and rows[name] != row:
            raise ValueError(f"{name} collapses to conflicting rows: {rows[name]} and {row}")
        rows[name] = row
    return list(rows.values())


def parse(text: str) -> list[Row]:
    return [Row(**m.groupdict()) for m in ROW.finditer(text) if m["name"] != SEPARATOR]


def differences(expected: list[Row], found: list[Row]) -> list[str]:
    want = {r.name: r for r in expected}
    have = {r.name: r for r in found}
    out = [f"missing row: | {want[n].name} | {want[n].code} | {want[n].status} |" for n in want.keys() - have.keys()]
    out += [f"unrecorded row: | {have[n].name} | {have[n].code} | {have[n].status} |" for n in have.keys() - want.keys()]
    out += [
        f"{n}: table says {have[n].code}/{have[n].status}, metadata says {want[n].code}/{want[n].status}"
        for n in want.keys() & have.keys()
        if want[n] != have[n]
    ]
    common = want.keys() & have.keys()
    if [r.name for r in expected if r.name in common] != [r.name for r in found if r.name in common]:
        out.append("row order does not follow the metadata")
    return sorted(out)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("metadata", type=Path)
    parser.add_argument("readme", type=Path, nargs="+")
    args = parser.parse_args(argv)

    import yaml

    expected = derive(yaml.safe_load(args.metadata.read_text()))
    failed = False
    for readme in args.readme:
        found = parse(readme.read_text())
        if not found:
            print(f"{readme}: no compatibility table found", file=sys.stderr)
            failed = True
            continue
        problems = differences(expected, found)
        for problem in problems:
            print(f"{readme}: {problem}", file=sys.stderr)
        failed = failed or bool(problems)
        if not problems:
            print(f"{readme}: {len(found)} rows match {args.metadata}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
