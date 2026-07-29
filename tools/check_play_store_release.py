#!/usr/bin/env python3
"""Report Play Store releases that play-store-compatibility.yaml does not record."""

from __future__ import annotations

import argparse
import json
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import NamedTuple
from xml.etree import ElementTree

VERSION = re.compile(r"^(\d{2,3})\.(\d{1,2})\.(\d{1,2})$")

# Below this, the source layout moved and the run must fail rather than report nothing new.
MINIMUM_PLAUSIBLE_RESULTS = 5

# A major this far ahead comes from a bad parse or a repackaged upload.
MAXIMUM_MAJOR_JUMP = 2


class UpstreamError(RuntimeError):
    pass


class Release(NamedTuple):
    version: str
    link: str | None = None
    published: str | None = None


def parse_apkmirror_feed(body: str) -> list[Release]:
    """APKMirror's per-app RSS feed."""
    try:
        root = ElementTree.fromstring(body)
    except ElementTree.ParseError as error:
        raise UpstreamError(f"apkmirror feed is not valid XML: {error}") from error

    releases = []
    for item in root.iter("item"):
        title = (item.findtext("title") or "").strip()
        matched = re.search(r"Google Play Store (\d{2,3}\.\d{1,2}\.\d{1,2})\b", title)
        if not matched:
            continue
        releases.append(
            Release(
                version=matched.group(1),
                link=(item.findtext("link") or "").strip() or None,
                published=(item.findtext("pubDate") or "").strip() or None,
            )
        )
    return releases


def parse_apkcombo(body: str) -> list[Release]:
    return [
        Release(version=version)
        for version in re.findall(
            r'<span class="vername">Google Play Store (\d{2,3}\.\d{1,2}\.\d{1,2})</span>', body
        )
    ]


SOURCES = {
    "apkmirror": parse_apkmirror_feed,
    "apkcombo": parse_apkcombo,
}

SOURCE_URLS = {
    "apkmirror": "https://www.apkmirror.com/apk/google-inc/google-play-store/feed/",
    "apkcombo": "https://apkcombo.com/google-play-store/com.android.vending/old-versions/",
}


def version_tuple(version: str) -> tuple[int, int, int]:
    matched = VERSION.match(version)
    if not matched:
        raise ValueError(f"not a Play Store version: {version!r}")
    return tuple(int(part) for part in matched.groups())


def recorded_versions(metadata: dict) -> set[str]:
    """The release part of each recorded version_name, e.g. '52.4.41-31 [0] [PR] 9' -> '52.4.41'."""
    versions = set()
    for release in metadata.get("releases") or []:
        candidate = str(release.get("version_name", "")).split("-")[0].strip()
        if VERSION.match(candidate):
            versions.add(candidate)
    return versions


def detect(listing: str, metadata: dict, source: str) -> dict:
    parser = SOURCES.get(source)
    if parser is None:
        raise UpstreamError(f"unknown source {source!r}, expected one of {sorted(SOURCES)}")

    found = [release for release in parser(listing) if VERSION.match(release.version)]
    by_version = {release.version: release for release in found}
    if len(by_version) < MINIMUM_PLAUSIBLE_RESULTS:
        raise UpstreamError(
            f"{source} yielded {len(by_version)} versions, expected at least "
            f"{MINIMUM_PLAUSIBLE_RESULTS}. The source layout has probably changed."
        )

    known = recorded_versions(metadata)
    if not known:
        raise UpstreamError("compatibility metadata records no releases")

    newest_known = max(known, key=version_tuple)
    ceiling = version_tuple(newest_known)[0] + MAXIMUM_MAJOR_JUMP
    plausible = {
        version: release
        for version, release in by_version.items()
        if version_tuple(version)[0] <= ceiling
    }
    if not plausible:
        raise UpstreamError(f"{source} listed no release at or below major {ceiling}")

    unrecorded = sorted(
        (
            release
            for version, release in plausible.items()
            if version not in known and version_tuple(version) > version_tuple(newest_known)
        ),
        key=lambda release: version_tuple(release.version),
    )
    newest_upstream = max(plausible, key=version_tuple)
    newest_new = unrecorded[-1] if unrecorded else None

    return {
        "new_release": bool(unrecorded),
        "version_name": newest_new.version if newest_new else None,
        "release_url": newest_new.link if newest_new else None,
        "published": newest_new.published if newest_new else None,
        "unrecorded": [release.version for release in unrecorded],
        "newest_recorded": newest_known,
        "newest_upstream": newest_upstream,
        "source": source,
        "source_url": SOURCE_URLS.get(source),
        "detected_at": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--listing", required=True, type=Path, help="saved upstream response")
    parser.add_argument("--metadata", required=True, type=Path)
    parser.add_argument("--source", default="apkmirror", choices=sorted(SOURCES))
    arguments = parser.parse_args(argv)

    import yaml

    try:
        verdict = detect(
            listing=arguments.listing.read_text(),
            metadata=yaml.safe_load(arguments.metadata.read_text()),
            source=arguments.source,
        )
    except (UpstreamError, ValueError) as error:
        print(f"::error::{error}", file=sys.stderr)
        return 1

    print(json.dumps(verdict, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
