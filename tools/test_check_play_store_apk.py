#!/usr/bin/env python3
from __future__ import annotations

import io
import tempfile
import unittest
import zipfile
from pathlib import Path

import check_play_store_apk as check

RESOLVER = '''
    private const val GENERIC_CARD_MODEL = "not.an.anchor"
    private const val CACHE_MANAGER_ANCHOR =
        "Failed to load a StreamNode with an ID and no URL."
    private const val DATA_STORE_CALLBACK_ANCHOR =
        "resolved is null for: %s \\n  list: %s " +
            "\\n other: %s"
    private val appsHomeAnchors =
        listOf(
            "https://play.google.com/store/apps",
            "HOME_UI_MODEL_RECEIVED",
        )
    private val primitiveTypes = setOf("int")
'''

LABELS = '''
internal object PlayStoreLabels {
    const val LIBRARY = "Library"
    const val RATINGS_AND_REVIEWS = "Ratings & reviews"
'''


class ParsingTest(unittest.TestCase):
    def test_anchor_constants_and_lists_are_extracted(self) -> None:
        found = check.anchors(RESOLVER)
        self.assertEqual(
            found["CACHE_MANAGER_ANCHOR"],
            ["Failed to load a StreamNode with an ID and no URL."],
        )
        self.assertEqual(
            found["DATA_STORE_CALLBACK_ANCHOR"],
            ["resolved is null for: %s \n  list: %s \n other: %s"],
        )
        self.assertEqual(
            found["appsHomeAnchors"],
            ["https://play.google.com/store/apps", "HOME_UI_MODEL_RECEIVED"],
        )
        self.assertNotIn("GENERIC_CARD_MODEL", found)
        self.assertNotIn("primitiveTypes", found)

    def test_labels_are_extracted(self) -> None:
        self.assertEqual(
            check.labels(LABELS),
            {"LIBRARY": "Library", "RATINGS_AND_REVIEWS": "Ratings & reviews"},
        )

    def test_normalization_matches_the_module(self) -> None:
        self.assertEqual(check.normalize("Ratings & reviews"), "ratings and reviews")
        self.assertEqual(check.normalize("  Ratings  and Reviews "), "ratings and reviews")

    def test_dex_string_pool_is_read_from_every_dex(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "base.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"\x05first")
                archive.writestr("classes2.dex", b"\x06second")
                archive.writestr("res/raw/other.bin", b"third")
            pool = check.dex_strings(apk)
        self.assertIn(b"first", pool)
        self.assertIn(b"second", pool)
        self.assertNotIn(b"third", pool)


class ReportTest(unittest.TestCase):
    def test_a_missing_anchor_fails_the_check(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "base.apk"
            with zipfile.ZipFile(apk, "w") as archive:
                archive.writestr("classes.dex", b"nothing useful")
            output = io.StringIO()
            from contextlib import redirect_stdout

            with redirect_stdout(output):
                status = check.main([str(apk), "--skip-labels"])
        self.assertEqual(status, 1)
        self.assertIn("MISSING", output.getvalue())


if __name__ == "__main__":
    unittest.main()
