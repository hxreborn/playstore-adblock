#!/usr/bin/env python3
from __future__ import annotations

import unittest

from check_play_store_release import (
    UpstreamError,
    detect,
    parse_apkcombo,
    parse_apkmirror_feed,
    recorded_versions,
    version_tuple,
)

RECORDED = ("52.4.41", "52.3.32", "52.2.25", "51.9.18", "51.0.19", "50.7.37")


def feed(*versions: str) -> str:
    items = "".join(
        f"<item>"
        f"<title>Google Play Store {version} by Google LLC</title>"
        f"<link>https://www.apkmirror.com/apk/google-inc/google-play-store/"
        f"google-play-store-{version.replace('.', '-')}-release/</link>"
        f"<pubDate>Sun, 26 Jul 2026 19:34:04 +0000</pubDate>"
        f"</item>"
        for version in versions
    )
    return f"<rss version='2.0'><channel>{items}</channel></rss>"


def html(*versions: str) -> str:
    rows = "".join(
        f'<span class="vername">Google Play Store {version}</span>' for version in versions
    )
    return f"<html><body>{rows}</body></html>"


def metadata(*versions: str) -> dict:
    return {"releases": [{"version_name": f"{version}-31 [0] [PR] 953053140"} for version in versions]}


class ParseApkmirrorFeed(unittest.TestCase):
    def test_reads_version_link_and_publication_date(self):
        release = parse_apkmirror_feed(feed("52.4.41"))[0]
        self.assertEqual("52.4.41", release.version)
        self.assertIn("google-play-store-52-4-41-release", release.link)
        self.assertEqual("Sun, 26 Jul 2026 19:34:04 +0000", release.published)

    def test_ignores_an_item_that_is_not_a_release(self):
        body = "<rss><channel><item><title>Some announcement</title></item></channel></rss>"
        self.assertEqual([], parse_apkmirror_feed(body))

    def test_rejects_a_response_that_is_not_well_formed_xml(self):
        with self.assertRaises(UpstreamError):
            parse_apkmirror_feed('<html><meta charset="utf-8"><br></html>')

    def test_a_well_formed_page_that_is_not_the_feed_yields_no_releases(self):
        self.assertEqual([], parse_apkmirror_feed("<html><body>blocked</body></html>"))


class ParseApkcombo(unittest.TestCase):
    def test_reads_every_version_row(self):
        self.assertEqual(
            ["52.4.41", "52.3.32"],
            [release.version for release in parse_apkcombo(html("52.4.41", "52.3.32"))],
        )

    def test_ignores_markup_with_no_version_row(self):
        self.assertEqual([], parse_apkcombo("<html><body>nothing here</body></html>"))


class ReadMetadata(unittest.TestCase):
    def test_takes_the_release_part_of_a_version_name(self):
        self.assertEqual({"52.4.41"}, recorded_versions(metadata("52.4.41")))

    def test_ignores_a_malformed_version_name(self):
        self.assertEqual(set(), recorded_versions({"releases": [{"version_name": "nonsense"}]}))

    def test_tolerates_metadata_with_no_releases(self):
        self.assertEqual(set(), recorded_versions({}))


class CompareVersions(unittest.TestCase):
    def test_orders_numerically_rather_than_lexically(self):
        self.assertGreater(version_tuple("52.10.1"), version_tuple("52.9.9"))

    def test_rejects_something_that_is_not_a_release(self):
        with self.assertRaises(ValueError):
            version_tuple("latest")


class DetectNewRelease(unittest.TestCase):
    def test_reports_a_genuinely_newer_release_with_its_page(self):
        verdict = detect(feed("52.5.30", *RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertTrue(verdict["new_release"])
        self.assertEqual("52.5.30", verdict["version_name"])
        self.assertIn("google-play-store-52-5-30-release", verdict["release_url"])

    def test_reports_nothing_when_upstream_matches_the_metadata(self):
        verdict = detect(feed(*RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertFalse(verdict["new_release"])
        self.assertIsNone(verdict["version_name"])

    def test_suppresses_a_release_already_recorded(self):
        recorded = ("52.5.30", *RECORDED)
        self.assertFalse(detect(feed(*recorded), metadata(*recorded), "apkmirror")["new_release"])

    def test_ignores_an_older_release_a_stale_mirror_lists_first(self):
        verdict = detect(feed("40.1.1", *RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertFalse(verdict["new_release"])

    def test_ignores_an_implausible_major_from_a_repackaged_upload(self):
        verdict = detect(feed("99.0.1", *RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertFalse(verdict["new_release"])
        self.assertEqual("52.4.41", verdict["newest_upstream"])

    def test_accepts_the_next_major(self):
        verdict = detect(feed("53.0.1", *RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertTrue(verdict["new_release"])
        self.assertEqual("53.0.1", verdict["version_name"])

    def test_reports_every_unrecorded_release_not_only_the_newest(self):
        verdict = detect(feed("52.5.30", "52.6.10", *RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertEqual(["52.5.30", "52.6.10"], verdict["unrecorded"])

    def test_records_the_source_and_a_detection_timestamp(self):
        verdict = detect(feed(*RECORDED), metadata(*RECORDED), "apkmirror")
        self.assertEqual("apkmirror", verdict["source"])
        self.assertRegex(verdict["detected_at"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")

    def test_works_the_same_through_the_fallback_source(self):
        verdict = detect(html("52.5.30", *RECORDED), metadata(*RECORDED), "apkcombo")
        self.assertTrue(verdict["new_release"])
        self.assertEqual("52.5.30", verdict["version_name"])
        self.assertIsNone(verdict["release_url"])


class RejectBadInput(unittest.TestCase):
    def test_a_layout_change_fails_instead_of_reading_as_nothing_new(self):
        with self.assertRaises(UpstreamError):
            detect(feed(), metadata(*RECORDED), "apkmirror")

    def test_a_truncated_response_fails(self):
        with self.assertRaises(UpstreamError):
            detect(feed("52.4.41"), metadata(*RECORDED), "apkmirror")

    def test_empty_metadata_fails_rather_than_flagging_every_release(self):
        with self.assertRaises(UpstreamError):
            detect(feed(*RECORDED), {"releases": []}, "apkmirror")

    def test_an_unknown_source_fails(self):
        with self.assertRaises(UpstreamError):
            detect(feed(*RECORDED), metadata(*RECORDED), "someplace-else")


if __name__ == "__main__":
    unittest.main()
