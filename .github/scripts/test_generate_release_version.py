import contextlib
from datetime import datetime, timedelta, timezone
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import generate_release_version as release


NOW = datetime(2026, 10, 7, tzinfo=timezone.utc)


class ReleaseVersionTests(unittest.TestCase):
    def test_first_calendar_release_ignores_legacy_versions(self):
        self.assertEqual(
            release.generate_version(NOW, ["v0.0.1-beta", "v1.2.3"]),
            ("2026.10.7.0", 9_776_001),
        )

    def test_same_day_uses_maximum_index_not_count_or_string_sort(self):
        self.assertEqual(
            release.generate_version(NOW, ["v2026.10.7.9", "v2026.10.7.12", "v2026.10.7.9"]),
            ("2026.10.7.13", 9_776_014),
        )

    def test_taipei_midnight_changes_date_before_utc_midnight(self):
        before = datetime(2026, 10, 6, 15, 59, 59, tzinfo=timezone.utc)
        after = before + timedelta(seconds=1)
        self.assertEqual(release.generate_version(before, [])[0], "2026.10.6.0")
        self.assertEqual(release.generate_version(after, [])[0], "2026.10.7.0")

    def test_next_day_resets_index_and_increases_android_code(self):
        today_name, today_code = release.generate_version(NOW, ["v2026.10.7.998"])
        tomorrow_name, tomorrow_code = release.generate_version(
            NOW + timedelta(days=1), [f"v{today_name}"]
        )
        self.assertEqual(today_name, "2026.10.7.999")
        self.assertEqual(tomorrow_name, "2026.10.8.0")
        self.assertGreater(tomorrow_code, today_code)

    def test_year_rollover_is_monotonic(self):
        now = datetime(2026, 12, 31, tzinfo=timezone.utc)
        old_name, old_code = release.generate_version(now, [])
        new_name, new_code = release.generate_version(now + timedelta(days=1), [f"v{old_name}"])
        self.assertEqual(new_name, "2027.1.1.0")
        self.assertGreater(new_code, old_code)

    def test_future_release_prevents_downgrade(self):
        with self.assertRaisesRegex(ValueError, "later than"):
            release.generate_version(NOW, ["v2026.10.8.0"])

    def test_daily_capacity_fails_instead_of_colliding_with_next_day(self):
        with self.assertRaisesRegex(ValueError, "already occupied"):
            release.generate_version(NOW, ["v2026.10.7.999"])
        with self.assertRaisesRegex(ValueError, "daily version limit"):
            release.generate_version(NOW, ["v2026.10.6.1000"])

    def test_invalid_calendar_tag_is_rejected(self):
        with self.assertRaises(ValueError):
            release.generate_version(NOW, ["v2026.2.30.0"])

    def test_android_version_code_overflow_is_rejected(self):
        future = datetime.combine(
            release.EPOCH + timedelta(days=2_100_000), datetime.min.time(), timezone.utc
        )
        with self.assertRaisesRegex(ValueError, "Android range"):
            release.generate_version(future, [])

    def test_date_before_epoch_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "Android range"):
            release.generate_version(datetime(1999, 12, 31, tzinfo=timezone.utc), [])

    def test_cli_includes_drafts_and_annotated_tags_in_outputs(self):
        with tempfile.TemporaryDirectory() as directory:
            tags_file = Path(directory) / "tags.txt"
            releases_file = Path(directory) / "releases.txt"
            tags_file.write_text(
                "abc\trefs/tags/v2026.10.7.0\ndef\trefs/tags/v2026.10.7.0^{}\n",
                encoding="utf-8",
            )
            releases_file.write_text("v0.0.1-beta\nv2026.10.7.2\n", encoding="utf-8")
            arguments = [
                "generate_release_version.py", "--tags-file", str(tags_file),
                "--releases-file", str(releases_file),
            ]
            output = io.StringIO()
            with patch("sys.argv", arguments), patch.object(release, "datetime") as clock:
                clock.now.return_value = NOW
                with contextlib.redirect_stdout(output):
                    release.main()
            self.assertEqual(output.getvalue(), "VERSION_NAME=2026.10.7.3\nVERSION_CODE=9776004\n")


if __name__ == "__main__":
    unittest.main()
