#!/usr/bin/env python3
"""
Write a cap after every library in the Python SDK's install_requires (sdks/python/setup.py).

A library's cap is the latest version that its requirements allow and that has been on PyPI for at least three days.
It goes after the library's last requirement and repeats their environment markers, so it applies exactly where the
library is required. A release counts from the upload of its newest file, since PyPI accepts new files for an existing
release. Pre-releases and yanked releases never count. When no version that old is allowed, the cap is the oldest
allowed version: raising a lower bound to a fresh fix ships exactly that fix.

The script finds its caps by their comment, drops them all and writes a fresh one after each library, so adding or
removing a library adds or removes its cap. A library with an `# allow-latest: <why>` comment on one of its requirements
gets no cap, so its newest release is always allowed.

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
MIN_RELEASE_AGE_DAYS = 3
ALLOW_LATEST = "# allow-latest"

CAP_LINE = re.compile(
    r'^ *".*",  # (the latest version older than \d+ days'
    r"|the oldest allowed version \(none is older than \d+ days\)), released \d{4}-\d{2}-\d{2}\n",
    re.MULTILINE,
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


def cap_line(requirement_texts: list[str], indent: str, cutoff: datetime.datetime) -> str:
    library_requirements = [requirements.Requirement(text) for text in requirement_texts]
    name = library_requirements[0].name
    candidates = {
        release: uploaded
        for release, uploaded in releases(name).items()
        if any(requirement.specifier.contains(release) for requirement in library_requirements)
    }
    settled = [release for release, uploaded in candidates.items() if uploaded <= cutoff]
    if settled:
        cap = max(settled)
        reason = f"the latest version older than {MIN_RELEASE_AGE_DAYS} days"
    else:
        cap = min(candidates)
        reason = f"the oldest allowed version (none is older than {MIN_RELEASE_AGE_DAYS} days)"

    cap_text = f"{name}<={cap}"
    # A cap is a requirement too, so it repeats the marker of a library required only on some platforms. A library with
    # several requirements (litellm, split by Python version) is required everywhere, so its cap has no marker.
    if len(library_requirements) == 1 and library_requirements[0].marker is not None:
        cap_text += "; " + requirement_texts[0].split(";", 1)[1].strip()
    return f"{indent}{json.dumps(cap_text)},  # {reason}, released {candidates[cap].date()}\n"


def trailing_comma_line(lines: list[str], element: ast.expr) -> int:
    line = element.end_lineno - 1
    column = element.end_col_offset
    while "," not in lines[line][column:]:
        line += 1
        column = 0
    return line


def main() -> None:
    cutoff = datetime.datetime.now(datetime.UTC) - datetime.timedelta(days=MIN_RELEASE_AGE_DAYS)
    lines = CAP_LINE.sub("", SETUP_PY.read_text()).splitlines(keepends=True)

    setup_call = next(
        node
        for node in ast.walk(ast.parse("".join(lines)))
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "setup"
    )
    install_requires = next(keyword.value for keyword in setup_call.keywords if keyword.arg == "install_requires")

    elements_by_library: dict[str, list[ast.expr]] = {}
    for element in install_requires.elts:
        name = utils.canonicalize_name(requirements.Requirement(ast.literal_eval(element)).name)
        elements_by_library.setdefault(name, []).append(element)

    # From the last library up, so the line numbers of the ones above stay valid.
    for elements in sorted(elements_by_library.values(), key=lambda elements: elements[-1].end_lineno, reverse=True):
        comma_lines = [trailing_comma_line(lines, element) for element in elements]
        if any(
            ALLOW_LATEST in line
            for element, comma_line in zip(elements, comma_lines)
            for line in lines[element.lineno - 1 : comma_line + 1]
        ):
            continue
        indent = re.match(r" *", lines[comma_lines[-1]])[0]
        requirement_texts = [ast.literal_eval(element) for element in elements]
        lines.insert(comma_lines[-1] + 1, cap_line(requirement_texts, indent, cutoff))

    SETUP_PY.write_text("".join(lines))


if __name__ == "__main__":
    main()
