import hashlib
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import check_release_assets as release


class CheckReleaseAssetsTest(unittest.TestCase):
    version = "2.0.0"
    core = "playstore-adblock-v2.0.0-core-arm64-v8a.apk"
    full = "playstore-adblock-v2.0.0-full-arm64-v8a.apk"
    legacy = "playstore-adblock-v2.0.0-arm64-v8a.apk"
    sums = "playstore-adblock-v2.0.0-SHA256SUMS.txt"

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.directory = Path(temporary.name)
        for name in (self.core, self.full):
            (self.directory / name).write_bytes(name.encode())
        self.lines = [
            f"{hashlib.sha256(name.encode()).hexdigest()}  {name}\n"
            for name in (self.core, self.full)
        ]
        self.assets = [self.core, self.full, self.sums]

    def test_both_named_apks_and_their_manifest_are_selected(self):
        self.assertEqual(self.assets, release.expected_assets(self.version, self.assets + ["notes.txt"]))

    def test_a_historical_single_apk_is_selected(self):
        self.assertEqual([self.legacy], release.expected_assets(self.version, [self.legacy]))

    def test_a_partial_release_cannot_fall_back_to_legacy(self):
        with self.assertRaises(ValueError):
            release.expected_assets(self.version, [self.core, self.legacy])

    def test_an_extra_apk_or_missing_manifest_is_refused(self):
        for names in (self.assets + ["extra.apk"], [self.core, self.full]):
            with self.subTest(names=names), self.assertRaises(ValueError):
                release.expected_assets(self.version, names)

    def test_both_checksums_pass(self):
        (self.directory / self.sums).write_text("".join(self.lines))
        release.verify_checksums(self.directory, self.assets)

    def test_missing_duplicate_and_external_checksum_entries_are_refused(self):
        for lines in (
            self.lines[:1],
            self.lines + self.lines[:1],
            self.lines + ["0" * 64 + "  ../outside.apk\n"],
        ):
            with self.subTest(lines=lines), self.assertRaises(ValueError):
                (self.directory / self.sums).write_text("".join(lines))
                release.verify_checksums(self.directory, self.assets)

    def test_changed_apk_bytes_are_refused(self):
        (self.directory / self.sums).write_text("".join(self.lines))
        (self.directory / self.full).write_bytes(b"changed")
        with self.assertRaises(ValueError):
            release.verify_checksums(self.directory, self.assets)

    def badging(self):
        return (
            f"package: name='{release.PACKAGE}' versionCode='20000' versionName='2.0.0'\n"
            "native-code: 'arm64-v8a'\n"
        )

    def verify(self):
        release.verify_apk(self.directory / self.core, self.version, 20000, Path("/tools"))

    def test_the_release_certificate_passes(self):
        cert = f"Signer #1 certificate SHA-256 digest: {release.RELEASE_SIGNER}\n"
        with patch.object(subprocess, "check_output", side_effect=[self.badging(), cert]):
            self.verify()

    def test_the_current_build_tools_certificate_output_passes(self):
        cert = f"V2 Signer: certificate SHA-256 digest: {release.RELEASE_SIGNER}\n"
        with patch.object(subprocess, "check_output", side_effect=[self.badging(), cert]):
            self.verify()

    def test_a_valid_but_wrong_certificate_is_refused(self):
        with patch.object(subprocess, "check_output", side_effect=[self.badging(), "Signer #1 certificate SHA-256 digest: " + "0" * 64 + "\n"]):
            with self.assertRaises(ValueError):
                self.verify()

    def test_signature_verification_failure_is_propagated(self):
        with patch.object(subprocess, "check_output", side_effect=[self.badging(), subprocess.CalledProcessError(1, "apksigner")]):
            with self.assertRaises(subprocess.CalledProcessError):
                self.verify()

    def test_a_wrong_version_or_abi_is_refused(self):
        for badging in (self.badging().replace("20000", "20001"), self.badging().replace("arm64-v8a", "x86_64")):
            with self.subTest(badging=badging), patch.object(subprocess, "check_output", return_value=badging):
                with self.assertRaises(ValueError):
                    self.verify()


if __name__ == "__main__":
    unittest.main()
