#!/usr/bin/env python3
import argparse
import hashlib
import re
import subprocess
import sys
from pathlib import Path

RELEASE_SIGNER = "32b98dc709a7ee15fae073493a1aeca5d428316cc0d8b8f878eed64ba37f3297"
PACKAGE = "eu.hxreborn.gplayadblock"


def expected_assets(version, names):
    core = f"playstore-adblock-v{version}-core-arm64-v8a.apk"
    full = f"playstore-adblock-v{version}-full-arm64-v8a.apk"
    legacy = f"playstore-adblock-v{version}-arm64-v8a.apk"
    sums = f"playstore-adblock-v{version}-SHA256SUMS.txt"
    apks = [name for name in names if name.endswith(".apk")]
    if sorted(apks) == sorted([core, full]) and sums in names:
        return [core, full, sums]
    if apks == [legacy] and sums not in names:
        return [legacy]
    raise ValueError(f"unexpected release APK set or missing checksum manifest: {apks}")


def verify_checksums(directory, assets):
    if len(assets) == 1:
        return
    expected = set(assets[:2])
    recorded = {}
    for line in (directory / assets[2]).read_text().splitlines():
        match = re.fullmatch(r"([0-9a-f]{64})  (.+)", line)
        if match is None or match[2] not in expected or match[2] in recorded:
            raise ValueError(f"invalid checksum entry: {line}")
        recorded[match[2]] = match[1]
    if set(recorded) != expected:
        raise ValueError("checksum manifest must cover both APKs exactly once")
    for name, digest in recorded.items():
        if hashlib.sha256((directory / name).read_bytes()).hexdigest() != digest:
            raise ValueError(f"checksum mismatch for {name}")


def verify_apk(apk, version, code, build_tools):
    badging = subprocess.check_output([str(build_tools / "aapt2"), "dump", "badging", str(apk)], text=True)
    package = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", badging, re.M)
    if package is None or package.groups() != (PACKAGE, str(code), version):
        raise ValueError(f"{apk} has the wrong package or version")
    if "native-code: 'arm64-v8a'" not in badging.splitlines():
        raise ValueError(f"{apk} is not arm64-v8a only")
    certificates = subprocess.check_output(
        [str(build_tools / "apksigner"), "verify", "--print-certs", str(apk)], text=True
    )
    signers = re.findall(r"^(?:Signer #\d+|V\d+ Signer:) certificate SHA-256 digest: ([0-9a-f]{64})$", certificates, re.M)
    if set(signers) != {RELEASE_SIGNER}:
        raise ValueError(f"{apk} is not signed with the release certificate")


def main():
    parser = argparse.ArgumentParser(description="Validate release APK assets")
    commands = parser.add_subparsers(dest="command", required=True)
    select = commands.add_parser("select")
    select.add_argument("version")
    verify = commands.add_parser("verify")
    verify.add_argument("version")
    verify.add_argument("code", type=int)
    verify.add_argument("directory", type=Path)
    verify.add_argument("--build-tools", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "select":
        print("\n".join(expected_assets(args.version, sys.stdin.read().splitlines())))
        return
    assets = expected_assets(args.version, [entry.name for entry in args.directory.iterdir()])
    verify_checksums(args.directory, assets)
    for name in assets:
        if name.endswith(".apk"):
            verify_apk(args.directory / name, args.version, args.code, args.build_tools)
            print(f"ok {name}: package, version, ABI and release certificate")


if __name__ == "__main__":
    main()
