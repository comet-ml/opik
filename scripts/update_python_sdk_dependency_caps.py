#!/usr/bin/env python3
"""
Regenerate the Python SDK's dependency caps from its requirements.

sdks/python/setup.py holds one cap per requirement in install_requires, between the "BEGIN dependency caps" and
"END dependency caps" comments. A cap is the newest release that satisfies its requirement and has been on PyPI for at
least three days, and it repeats the requirement's environment marker, so it applies exactly where the requirement
does. A release counts from the upload of its newest file, since PyPI accepts new files for an existing release.
Pre-releases and yanked releases never count. When no release that old satisfies a requirement, its cap is the oldest
release that does: raising a lower bound to a fresh fix ships exactly that fix.

Usage:
  python scripts/update_python_sdk_dependency_caps.py

Needs `packaging`. Exits non-zero without writing anything when PyPI can't be reached.
"""

import ast
import datetime
import functools
import json
import re
import urllib.request
from pathlib import Path

from packaging import requirements, utils, version

SETUP_PY = Path(__file__).resolve().parent.parent / "sdks" / "python" / "setup.py"
MIN_RELEASE_AGE = datetime.timedelta(days=3)

CAPS_SECTION = re.compile(
    r"^(?P<indent> *)# BEGIN dependency caps\n.*?^ *# END dependency caps$", re.DOTALL | re.MULTILINE
)


@functools.cache
def releases(package_name: str) -> dict[version.Version, datetime.datetime]:
    request = urllib.request.Request(
        f"https://pypi.org/simple/{utils.canonicalize_name(package_name)}/",
        headers={"Accept": "application/vnd.pypi.simple.v1+json"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        files = json.load(response)["files"]

    newest_upload_by_version: dict[version.Version, datetime.datetime] = {}
    yanked_versions: set[version.Version] = set()
    for file in files:
        try:
            if file["filename"].endswith(".whl"):
                file_version = utils.parse_wheel_filename(file["filename"])[1]
            else:
                file_version = utils.parse_sdist_filename(file["filename"])[1]
        except (utils.InvalidWheelFilename, utils.InvalidSdistFilename):
            continue  # eggs, Windows installers and other legacy formats
        if file.get("yanked"):
            yanked_versions.add(file_version)
        uploaded = datetime.datetime.fromisoformat(file["upload-time"])
        newest_upload_by_version[file_version] = max(uploaded, newest_upload_by_version.get(file_version, uploaded))

    return {
        release: uploaded
        for release, uploaded in newest_upload_by_version.items()
        if not release.is_prerelease and release not in yanked_versions
    }


def cap_lines(requirement_text: str, indent: str, cutoff: datetime.datetime) -> str:
    requirement = requirements.Requirement(requirement_text)
    candidates = {
        release: uploaded
        for release, uploaded in releases(requirement.name).items()
        if requirement.specifier.contains(release)
    }
    settled = [release for release, uploaded in candidates.items() if uploaded <= cutoff]
    cap = max(settled) if settled else min(candidates)
    head = f"{requirement.name}<={cap}"
    released = f"  # released {candidates[cap].date()}"

    if requirement.marker is None:
        return f"{indent}{json.dumps(head)},{released}"
    # The marker as written in setup.py, split before each `and`/`or` like the requirements there.
    marker_text = " " + requirement_text.split(";", 1)[1].strip()
    fragments = [f"{indent}    {json.dumps(fragment)}" for fragment in re.split(r"(?= and | or )", marker_text)]
    return "\n".join([f"{indent}(", f"{indent}    {json.dumps(head + ';')}{released}", *fragments, f"{indent}),"])


def main() -> None:
    cutoff = datetime.datetime.now(datetime.UTC) - MIN_RELEASE_AGE
    setup_py = SETUP_PY.read_text()
    section = CAPS_SECTION.search(setup_py)
    first_section_line = setup_py.count("\n", 0, section.start()) + 1
    last_section_line = setup_py.count("\n", 0, section.end()) + 1

    setup_call = next(
        node
        for node in ast.walk(ast.parse(setup_py))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "setup"
    )
    install_requires = next(keyword.value for keyword in setup_call.keywords if keyword.arg == "install_requires")
    requirement_texts = [
        ast.literal_eval(element)
        for element in install_requires.elts
        if not first_section_line <= element.lineno <= last_section_line
    ]

    indent = section["indent"]
    caps = "\n".join(cap_lines(requirement_text, indent, cutoff) for requirement_text in requirement_texts)
    SETUP_PY.write_text(
        setup_py[: section.start()]
        + f"{indent}# BEGIN dependency caps\n{caps}\n{indent}# END dependency caps"
        + setup_py[section.end() :]
    )


if __name__ == "__main__":
    main()
