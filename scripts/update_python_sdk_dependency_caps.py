#!/usr/bin/env python3
"""
Raise the Python SDK's dependency caps to the newest release of each package that has been on PyPI for at least
three days.

The caps sit between the "BEGIN dependency caps" and "END dependency caps" comments in sdks/python/setup.py. A release
counts from the upload of its newest file, since PyPI accepts new files for an existing release. Pre-releases and
yanked files never count. A cap is only ever raised, so one raised by hand to ship an urgent fix sooner stays.

Usage:
  python scripts/update_python_sdk_dependency_caps.py

Needs `packaging`. Exits non-zero without writing anything when PyPI can't be reached.
"""

import datetime
import json
import re
import urllib.request
from pathlib import Path

from packaging import utils, version

SETUP_PY = Path(__file__).resolve().parent.parent / "sdks" / "python" / "setup.py"
MIN_RELEASE_AGE = datetime.timedelta(days=3)

CAPS_SECTION = re.compile(r"# BEGIN dependency caps\n.*?# END dependency caps", re.DOTALL)
CAP = re.compile(r'"(?P<name>[A-Za-z0-9._-]+)<=(?P<version>[^";\s]+)')


def newest_settled_version(package_name: str, cutoff: datetime.datetime) -> version.Version:
    request = urllib.request.Request(
        f"https://pypi.org/simple/{utils.canonicalize_name(package_name)}/",
        headers={"Accept": "application/vnd.pypi.simple.v1+json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        files = json.load(response)["files"]

    newest_upload_by_version: dict[version.Version, datetime.datetime] = {}
    for file in files:
        if file.get("yanked"):
            continue
        try:
            if file["filename"].endswith(".whl"):
                file_version = utils.parse_wheel_filename(file["filename"])[1]
            else:
                file_version = utils.parse_sdist_filename(file["filename"])[1]
        except (utils.InvalidWheelFilename, utils.InvalidSdistFilename):
            continue  # eggs, Windows installers and other legacy formats
        uploaded = datetime.datetime.fromisoformat(file["upload-time"])
        newest_upload_by_version[file_version] = max(uploaded, newest_upload_by_version.get(file_version, uploaded))

    return max(
        file_version
        for file_version, uploaded in newest_upload_by_version.items()
        if not file_version.is_prerelease and uploaded <= cutoff
    )


def main() -> None:
    cutoff = datetime.datetime.now(datetime.UTC) - MIN_RELEASE_AGE
    setup_py = SETUP_PY.read_text()
    section = CAPS_SECTION.search(setup_py)

    def raise_cap(cap: re.Match) -> str:
        current = version.Version(cap["version"])
        settled = newest_settled_version(cap["name"], cutoff)
        if settled <= current:
            return cap[0]
        print(f"{cap['name']}: {current} -> {settled}")
        return f'"{cap["name"]}<={settled}'

    updated_section = CAP.sub(raise_cap, section[0])
    SETUP_PY.write_text(setup_py[: section.start()] + updated_section + setup_py[section.end() :])


if __name__ == "__main__":
    main()
