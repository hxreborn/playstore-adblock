#!/usr/bin/env python3
from __future__ import annotations

import unittest

from check_compat_tables import Row, derive, differences, parse

METADATA = {
    "releases": [
        {"version_name": "52.7.34-31 [0] [PR] 1", "version_code": 85273430, "status": "supported"},
        {"version_name": "52.7.34-34 [0] [PR] 1", "version_code": 85273440, "status": "supported"},
        {"version_name": "52.8.55-34 [0] [PR] 2", "version_code": 85285540, "status": "untested"},
    ]
}

TABLE = """
| Play Store | Version code | Status |
| --- | --- | --- |
| 52.7.34-XX | 852734XX | supported |
| 52.8.55-XX | 852855XX | untested |
"""


class DeriveTest(unittest.TestCase):
    def test_flavours_of_one_release_collapse_to_a_single_row(self):
        self.assertEqual(
            [Row("52.7.34-XX", "852734XX", "supported"), Row("52.8.55-XX", "852855XX", "untested")],
            derive(METADATA),
        )

    def test_flavours_disagreeing_on_status_are_rejected(self):
        clashing = {
            "releases": [
                METADATA["releases"][0],
                {"version_name": "52.7.34-34 [0] [PR] 1", "version_code": 85273440, "status": "partial"},
            ]
        }
        with self.assertRaises(ValueError):
            derive(clashing)


class ParseTest(unittest.TestCase):
    def test_the_separator_is_not_a_row(self):
        self.assertEqual(
            [Row("52.7.34-XX", "852734XX", "supported"), Row("52.8.55-XX", "852855XX", "untested")],
            parse(TABLE),
        )


class DifferencesTest(unittest.TestCase):
    def test_a_table_matching_the_metadata_reports_nothing(self):
        self.assertEqual([], differences(derive(METADATA), parse(TABLE)))

    def test_a_release_absent_from_the_table_is_reported(self):
        dropped = TABLE.replace("| 52.8.55-XX | 852855XX | untested |\n", "")
        self.assertEqual(
            ["missing row: | 52.8.55-XX | 852855XX | untested |"],
            differences(derive(METADATA), parse(dropped)),
        )

    def test_a_table_row_with_no_metadata_entry_is_reported(self):
        extra = TABLE + "| 53.0.27-XX | 853027XX | supported |\n"
        self.assertEqual(
            ["unrecorded row: | 53.0.27-XX | 853027XX | supported |"],
            differences(derive(METADATA), parse(extra)),
        )

    def test_a_status_the_metadata_does_not_carry_is_reported(self):
        stale = TABLE.replace("| 52.8.55-XX | 852855XX | untested |", "| 52.8.55-XX | 852855XX | supported |")
        self.assertEqual(
            ["52.8.55-XX: table says 852855XX/supported, metadata says 852855XX/untested"],
            differences(derive(METADATA), parse(stale)),
        )

    def test_rows_out_of_metadata_order_are_reported(self):
        swapped = """
| --- | --- | --- |
| 52.8.55-XX | 852855XX | untested |
| 52.7.34-XX | 852734XX | supported |
"""
        self.assertEqual(
            ["row order does not follow the metadata"],
            differences(derive(METADATA), parse(swapped)),
        )


if __name__ == "__main__":
    unittest.main()
