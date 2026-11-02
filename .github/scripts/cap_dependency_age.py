"""Limit each dependency of a Python package to versions that are at least N days old.

The SDK publish workflows run this on setup.py / pyproject.toml right before building. Each requirement
gets `<=` its newest version at least N days old, plus `!=` for any younger version below that, so the
published metadata admits no release younger than the cutoff: a compromised upload cannot reach
`pip install opik` users until it has been public long enough to be caught and yanked.
"""

import argparse
import ast
import dataclasses
import datetime
import http.client
import json
import os
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

from packaging.requirements import InvalidRequirement, Requirement
from packaging.specifiers import SpecifierSet
from packaging.utils import (
    InvalidSdistFilename,
    InvalidWheelFilename,
    canonicalize_name,
    parse_sdist_filename,
    parse_wheel_filename,
)
from packaging.version import InvalidVersion, Version

OWN_PACKAGES = {"opik", "opik-optimizer"}
INDEX_URL = "https://pypi.org/simple/{}/"
ATTEMPTS = 3


@dataclasses.dataclass(frozen=True)
class Release:
    # Newest file, not oldest: PyPI lets a maintainer add files to an old release, so a
    # hijacked account could slip a malicious wheel into a version that has been out for months.
    uploaded: datetime.datetime
    yanked: bool


@dataclasses.dataclass
class Entry:
    text: str
    start: int
    end: int
    quote: str


_index_cache: dict[str, dict[Version, Release]] = {}


def fetch_releases(name: str) -> dict[Version, Release]:
    """Every version of `name` with a file pip can use, yanked ones flagged; empty if the project doesn't exist."""
    if name in _index_cache:
        return _index_cache[name]
    request = urllib.request.Request(
        INDEX_URL.format(name),
        headers={"Accept": "application/vnd.pypi.simple.v1+json", "User-Agent": "opik-dependency-age-cap"},
    )
    for attempt in range(1, ATTEMPTS + 1):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                index = json.load(response)
            break
        except urllib.error.HTTPError as error:
            if error.code == 404:
                index = {"files": []}
                break
            if attempt == ATTEMPTS:
                raise SystemExit(f"::error::cannot read the PyPI index for {name}: {error}")
        except (OSError, ValueError, http.client.HTTPException) as error:
            if attempt == ATTEMPTS:
                raise SystemExit(f"::error::cannot read the PyPI index for {name}: {error}")
        time.sleep(5 * attempt)

    uploaded: dict[Version, datetime.datetime] = {}
    live: set[Version] = set()
    try:
        for file in index["files"]:
            filename = file["filename"]
            try:
                if filename.endswith(".whl"):
                    project, version = parse_wheel_filename(filename)[:2]
                else:
                    project, version = parse_sdist_filename(filename)
            except (InvalidWheelFilename, InvalidSdistFilename, InvalidVersion):
                continue  # eggs, installers and other files pip can't install
            # pip skips files named for another project, e.g. legacy `foo-1.0-2.tar.gz` parses as
            # project `foo-1-0`; counting them would add phantom versions and inflate the cap.
            if project != name:
                continue
            # Python 3.10's fromisoformat rejects a trailing "Z".
            at = datetime.datetime.fromisoformat(file["upload-time"].replace("Z", "+00:00"))
            if at.tzinfo is None:
                raise ValueError(f"upload-time {file['upload-time']!r} has no timezone")
            uploaded[version] = max(at, uploaded.get(version, at))
            if file.get("yanked", False) is False:
                live.add(version)
    except (KeyError, TypeError, AttributeError, ValueError) as error:
        raise SystemExit(f"::error::unexpected PyPI index data for {name}: {error!r}")
    releases = {version: Release(at, version not in live) for version, at in uploaded.items()}
    _index_cache[name] = releases
    return releases


def read_setup_py(source: str) -> list[Entry]:
    tree = ast.parse(source)
    calls = [
        node
        for node in ast.walk(tree)
        if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "setup"
    ]
    if len(calls) != 1:
        raise SystemExit(f"::error::expected one setup() call, found {len(calls)}")
    keywords = calls[0].keywords
    if any(keyword.arg is None for keyword in keywords):
        raise SystemExit("::error::setup() takes **kwargs, so its requirements can't be read")
    if "install_requires" not in {keyword.arg for keyword in keywords}:
        raise SystemExit("::error::setup() has no install_requires")
    nodes: list[ast.expr] = []
    for keyword in keywords:
        if keyword.arg == "install_requires" and isinstance(keyword.value, ast.List):
            nodes += keyword.value.elts
        elif keyword.arg == "extras_require" and isinstance(keyword.value, ast.Dict):
            for group in keyword.value.values:
                if not isinstance(group, ast.List):
                    raise SystemExit("::error::extras_require groups must be list literals")
                nodes += group.elts
        elif keyword.arg in ("install_requires", "extras_require"):
            raise SystemExit(f"::error::{keyword.arg} must be a literal")

    # ast column offsets count UTF-8 bytes, so splice the encoded source.
    line_starts = [0]
    for line in source.encode().splitlines(keepends=True):
        line_starts.append(line_starts[-1] + len(line))
    entries = []
    for node in nodes:
        if not (isinstance(node, ast.Constant) and isinstance(node.value, str)):
            raise SystemExit(f"::error::setup.py line {node.lineno}: requirements must be string literals")
        start = line_starts[node.lineno - 1] + node.col_offset
        end = line_starts[node.end_lineno - 1] + node.end_col_offset
        entries.append(Entry(node.value, start, end, quote=""))
    return entries


def read_pyproject(source: str) -> list[Entry]:
    import tomllib  # 3.11+; only the optimizer, built on 3.12, has a pyproject.toml to cap

    project = tomllib.loads(source).get("project", {})
    dynamic = {"dependencies", "optional-dependencies"} & set(project.get("dynamic", []))
    if dynamic:
        raise SystemExit(
            f"::error::pyproject.toml declares {', '.join(sorted(dynamic))} dynamic, so they can't be read"
        )
    texts = list(project.get("dependencies", []))
    for group in project.get("optional-dependencies", {}).values():
        texts += group
    data = source.encode()
    entries = []
    for text in dict.fromkeys(texts):
        spans = []
        for quote in ('"', "'"):
            needle = f"{quote}{text}{quote}".encode()
            at = data.find(needle)
            while at != -1:
                spans.append((at, at + len(needle), quote))
                at = data.find(needle, at + 1)
        if len(spans) != texts.count(text):
            raise SystemExit(
                f"::error::{text!r} is listed {texts.count(text)} time(s) but its quoted text appears "
                f"{len(spans)} time(s) in pyproject.toml, so it can't be rewritten safely"
            )
        entries += [Entry(text, start, end, quote) for start, end, quote in spans]
    return entries


def read_exceptions(path: Path) -> dict[str, Version]:
    exceptions: dict[str, Version] = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = raw.split("#", 1)[0].strip()
        if not line:
            continue
        problem = f"::error::{path}:{number}: expected <package>==<version>, got {raw.strip()!r}"
        try:
            requirement = Requirement(line)
        except InvalidRequirement:
            raise SystemExit(problem)
        pins = list(requirement.specifier)
        if (
            requirement.url
            or requirement.marker
            or requirement.extras
            or len(pins) != 1
            or pins[0].operator != "=="
            or "*" in pins[0].version
        ):
            raise SystemExit(problem)
        name = canonicalize_name(requirement.name)
        if name in exceptions:
            raise SystemExit(f"::error::{path}:{number}: {requirement.name} is listed twice")
        exceptions[name] = Version(pins[0].version)
    return exceptions


def add_bound(text: str, bound: str) -> str:
    """Append `bound` to the version part of `text`, leaving the rest exactly as written."""
    original = Requirement(text)
    head, separator, marker = text.partition(";")
    version_part = head.rstrip()
    joiner = "," if original.specifier else ""
    capped = f"{version_part}{joiner}{bound}{head[len(version_part) :]}{separator}{marker}"
    try:
        result = Requirement(capped)
    except InvalidRequirement:
        result = None
    if (
        result is None
        or result.name != original.name
        or result.extras != original.extras
        or str(result.marker) != str(original.marker)
        or result.specifier != original.specifier & SpecifierSet(bound)
    ):
        raise SystemExit(f"::error::could not add {bound} to {text!r} cleanly (got {capped!r})")
    return capped


def age(release: Release, now: datetime.datetime) -> str:
    days = (now - release.uploaded).days
    return "1 day" if days == 1 else f"{days} days"


def read_requirements(path: Path, source: str) -> list[Entry]:
    if path.name == "setup.py":
        return read_setup_py(source)
    if path.name == "pyproject.toml":
        return read_pyproject(source)
    raise SystemExit(f"::error::don't know how to read {path}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("file", type=Path, help="setup.py or pyproject.toml to cap in place")
    parser.add_argument("--exceptions", type=Path, required=True)
    parser.add_argument("--min-age-days", type=int, default=7)
    parser.add_argument("--check", action="store_true", help="report the caps without editing the file")
    args = parser.parse_args()

    source = args.file.read_text(encoding="utf-8")
    entries = read_requirements(args.file, source)
    # An empty list would publish with no caps at all and still pass.
    if not entries:
        raise SystemExit(f"::error::found no requirements in {args.file}")
    exceptions = read_exceptions(args.exceptions)
    now = datetime.datetime.now(datetime.timezone.utc)
    cutoff = now - datetime.timedelta(days=args.min_age_days)

    errors: list[str] = []
    rows: list[tuple[str, str, str, str]] = []
    capped: dict[str, str] = {}
    accepted: dict[str, int] = {name: 0 for name in exceptions}
    effective: dict[str, int] = {name: 0 for name in exceptions}
    dependencies: set[str] = set()
    for text in dict.fromkeys(entry.text for entry in entries):
        try:
            requirement = Requirement(text)
        except InvalidRequirement as error:
            raise SystemExit(f"::error::{text!r} is not a valid requirement: {error}")
        name = canonicalize_name(requirement.name)
        dependencies.add(name)
        if name in OWN_PACKAGES:
            rows.append((text, "—", "—", "own package, not capped"))
            continue
        releases = fetch_releases(name)
        # Explicit, because packaging's default admits a pre-release when it is the newest match, and a
        # `<=1.0.dev6` cap would then tell pip that pre-releases are wanted.
        prereleases = bool(requirement.specifier.prereleases)
        allowed = [
            v
            for v, r in releases.items()
            if not r.yanked and requirement.specifier.contains(v, prereleases=prereleases)
        ]
        cap = max((v for v in allowed if releases[v].uploaded <= cutoff), default=None)
        via = "age"
        excepted = exceptions.get(name)
        if excepted is not None and excepted in allowed:
            accepted[name] += 1
            if cap is None or excepted > cap:
                cap, via = excepted, "exceptions file"
                effective[name] += 1
        if cap is None:
            newest = max(allowed, default=None)
            if newest is None:
                errors.append(f"{text!r}: no published version matches it")
            else:
                errors.append(
                    f"{text!r}: no allowed version is {args.min_age_days} days old (newest is {newest}, "
                    f"{age(releases[newest], now)} old). To allow it now, add "
                    f"`{requirement.name}=={newest}` to {args.exceptions}"
                )
            continue
        # A cap only limits how new a version is, so a fresh backport below it (1.9.5 under <=2.0) would
        # still get through; exclude those as well, unless the exceptions file lets that version in.
        # Pre-releases too: pip and uv fall back to them when no final release fits, and a `!=` on one
        # doesn't make them wanted. Yanked ones too, as a yank can be undone after the build.
        fresh = sorted(
            v
            for v, r in releases.items()
            if v < cap and r.uploaded > cutoff and requirement.specifier.contains(v, prereleases=True)
        )
        if excepted in fresh and excepted in allowed:
            fresh.remove(excepted)
            effective[name] += 1
        bound = ",".join([f"<={cap}", *(f"!={v}" for v in fresh)])
        capped[text] = add_bound(text, bound)
        rows.append((text, bound, age(releases[cap], now), via))

    for name, version in exceptions.items():
        releases = fetch_releases(name)
        if version not in releases:
            errors.append(f"{args.exceptions}: {name}=={version} is not on PyPI")
        # The file is shared by both SDKs, so entries for the other one are expected here.
        elif name in dependencies:
            if releases[version].yanked:
                print(f"::warning::{args.exceptions}: {name}=={version} is yanked, so the entry is ignored")
            elif accepted[name] == 0:
                print(f"::warning::{args.exceptions}: no requirement on {name} accepts {version}")
            elif effective[name] == 0:
                print(
                    f"::notice::{args.exceptions}: {name}=={version} is now {args.min_age_days}+ days old, "
                    "so the entry changes nothing and can go"
                )

    table = [
        f"### Dependency age caps for `{args.file}` (minimum age {args.min_age_days} days)",
        "",
        "| Requirement | Bound | Cap's age | Via |",
        "|---|---|---|---|",
        *(f"| `{text}` | `{bound}` | {cap_age} | {via} |" for text, bound, cap_age, via in rows),
        "",
    ]
    print("\n".join(table))
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write("\n".join(table) + "\n")

    if errors:
        for error in errors:
            print(f"::error::{error}")
        sys.exit(1)

    data = source.encode()
    for entry in sorted(entries, key=lambda e: e.start, reverse=True):
        if entry.text not in capped:
            continue
        new = capped[entry.text]
        replacement = json.dumps(new, ensure_ascii=False) if not entry.quote else f"{entry.quote}{new}{entry.quote}"
        data = data[: entry.start] + replacement.encode() + data[entry.end :]
    # Read the result back the same way, so a splice in the wrong place fails here instead of
    # publishing metadata that only looks capped.
    rewritten = read_requirements(args.file, data.decode())
    if sorted(e.text for e in rewritten) != sorted(capped.get(e.text, e.text) for e in entries):
        raise SystemExit(f"::error::{args.file} doesn't read back as the capped requirements; nothing was written")
    # Only now, so --check (the release preflight) also covers the rewrite and the read-back.
    if args.check:
        return
    args.file.write_bytes(data)


if __name__ == "__main__":
    main()
