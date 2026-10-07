"""Generate calendar release versions without changing remote tags or releases."""

import argparse
from datetime import date, datetime, timedelta, timezone
from pathlib import Path
import re


RELEASE_TIMEZONE = timezone(timedelta(hours=8), "Asia/Taipei")
EPOCH = date(2000, 1, 1)
VERSIONS_PER_DAY = 1000
MAX_VERSION_CODE = 2_100_000_000
CALENDAR_TAG = re.compile(r"v(\d{4})\.(\d{1,2})\.(\d{1,2})\.(\d+)")


def generate_version(now: datetime, tags: list[str]) -> tuple[str, int]:
    today = now.astimezone(RELEASE_TIMEZONE).date()
    next_index = 0
    for tag in tags:
        match = CALENDAR_TAG.fullmatch(tag)
        if match is None:
            continue  # Legacy SemVer tags do not occupy calendar version numbers.
        year, month, day, index = map(int, match.groups())
        release_date = date(year, month, day)
        if release_date > today:
            raise ValueError(f"Existing release {tag} is later than today's release date")
        if index >= VERSIONS_PER_DAY:
            raise ValueError(f"Existing release {tag} exceeds the daily version limit")
        if release_date == today:
            next_index = max(next_index, index + 1)
    if next_index >= VERSIONS_PER_DAY:
        raise ValueError("All 1000 release version numbers for today are already occupied")

    version_name = f"{today.year}.{today.month}.{today.day}.{next_index}"
    # Reserve 1000 codes per date so tomorrow's first code exceeds today's last.
    version_code = (today - EPOCH).days * VERSIONS_PER_DAY + next_index + 1
    if not 0 < version_code <= MAX_VERSION_CODE:
        raise ValueError("Generated VERSION_CODE is outside the supported Android range")
    return version_name, version_code


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tags-file", type=Path, required=True)
    parser.add_argument("--releases-file", type=Path, required=True)
    args = parser.parse_args()
    # git ls-remote includes both tag refs and annotated-tag peel refs.
    tags = [
        line.split("\t", 1)[1].removeprefix("refs/tags/").removesuffix("^{}")
        for line in args.tags_file.read_text(encoding="utf-8").splitlines()
        if line
    ]
    # The releases list includes drafts, which may not yet have a remote tag.
    tags.extend(args.releases_file.read_text(encoding="utf-8").splitlines())
    version_name, version_code = generate_version(datetime.now(timezone.utc), tags)
    print(f"VERSION_NAME={version_name}")
    print(f"VERSION_CODE={version_code}")


if __name__ == "__main__":
    main()
