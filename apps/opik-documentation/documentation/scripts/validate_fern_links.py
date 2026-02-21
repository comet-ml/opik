#!/usr/bin/env python3
"""Validate and optionally fix internal Fern docs links.

The script validates markdown/html links in Fern docs against `docs.yml` routes,
canonical redirects, and docs image assets.
"""

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple
from urllib.parse import urlparse

import yaml


DEFAULT_ROOT = Path(__file__).resolve().parent.parent / "fern"
DEFAULT_DOCS_ROOT = DEFAULT_ROOT / "docs"
DEFAULT_DOCS_YML = DEFAULT_ROOT / "docs.yml"

LINK_PATTERNS = [
    re.compile(r"\[[^\]]*\]\(\s*([^\s)]+)\s*\)"),
    re.compile(r"href\s*=\s*[\"']([^\"']+)[\"']", re.IGNORECASE),
    re.compile(r"^\[[^\]]+\]:\s+([^\s]+)", re.MULTILINE),
]

SKIP_URL_PREFIXES = (
    "mailto:",
    "tel:",
    "javascript:",
    "cursor:",
    "#",
)

KNOWN_DOC_HOSTS = {
    "www.comet.com",
    "comet.com",
    "opik.docs.buildwithfern.com",
}


@dataclass
class Issue:
    file: Path
    line: int
    original: str
    suggestion: Optional[str]
    kind: str


def norm_slug(value: object) -> Optional[str]:
    value = str(value or "").strip()
    value = value.strip("/")
    return value or None


def redirect_to_regex(source: str) -> re.Pattern[str]:
    pattern = re.escape(source)
    pattern = pattern.replace(r"\:slug\*", "(.+)")
    pattern = pattern.replace(r"\:slug", "([^/]+)")
    return re.compile(rf"^{pattern}$")


def line_number(text: str, index: int) -> int:
    return text.count("\n", 0, index) + 1


def load_docs_config(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as fh:
        config = yaml.safe_load(fh)

    if not isinstance(config, dict):
        raise ValueError("docs.yml does not contain a valid YAML mapping")

    return config


def collect_routes(config: dict) -> Tuple[set[str], Dict[str, str], List[dict]]:
    routes: set[str] = set()
    path_to_route: Dict[str, str] = {}
    redirects: List[dict] = []

    tabs = config.get("tabs", {}) or {}

    def walk_navigation(items: Sequence[dict], base: List[str]) -> None:
        for item in items or []:
            if not isinstance(item, dict):
                continue

            section_slug = norm_slug(item.get("slug"))
            slug = section_slug

            if item.get("section") and item.get("contents") is not None:
                next_base = list(base)
                if not item.get("skip-slug") and slug:
                    next_base.append(slug)
                walk_navigation(item.get("contents", []), next_base)
                continue

            path = item.get("path") or item.get("changelog")
            slug = norm_slug(item.get("slug"))
            if not path or not slug:
                continue

            route = "/" + "/".join(x for x in (*base, slug) if x)
            route = route if route != "//" else "/"
            route = route.rstrip("/")
            route = route or "/"

            routes.add(route)
            path_to_route[str(path).strip("/")] = route

    for entry in config.get("navigation", []) or []:
        if not isinstance(entry, dict):
            continue

        base: List[str] = []
        tab = entry.get("tab")
        if tab:
            base_slug = norm_slug((tabs.get(tab) or {}).get("slug"))
            if base_slug:
                base.append(base_slug)

        walk_navigation(entry.get("layout", []), base)

    for redirect in config.get("redirects", []) or []:
        if not isinstance(redirect, dict):
            continue

        source = str(redirect.get("source", "")).strip()
        destination = str(redirect.get("destination", "")).strip()
        if not source or not destination:
            continue

        redirects.append(
            {
                "source": source,
                "destination": destination,
                "exact": ":slug" not in source and ":slug*" not in source,
                "regex": redirect_to_regex(source),
            }
        )

    return routes, path_to_route, redirects


def collect_img_routes(doc_root: Path) -> set[str]:
    img_root = doc_root.parent / "img"
    if not img_root.exists():
        return set()

    assets: set[str] = set()
    for path in img_root.rglob("**/*"):
        if not path.is_file():
            continue

        rel = path.relative_to(img_root).as_posix()
        assets.add(f"/img/{rel}")
        stem = rel.rsplit(".", maxsplit=1)
        if len(stem) == 2:
            assets.add(f"/img/{stem[0]}")

    return assets


def find_route(path: str, routes: set[str], path_to_route: Dict[str, str], redirects: List[dict]) -> Optional[str]:
    compact = path.rstrip("/") or "/"

    if compact in routes:
        return compact

    if compact in path_to_route:
        return path_to_route[compact]

    for redirect in redirects:
        if redirect["exact"] and redirect["source"] == compact:
            return redirect["destination"]
        if not redirect["exact"] and redirect["regex"].match(compact):
            return redirect["destination"]

    if compact.startswith("/docs/opik"):
        mapped = compact.replace("/docs/opik", "", 1)
        mapped = mapped or "/"
        mapped = mapped.rstrip("/") or "/"
        if mapped in routes:
            return mapped

    compact_lookup = compact.lstrip("/")
    for candidate in (compact_lookup, f"{compact_lookup}.md", f"{compact_lookup}.mdx"):
        route = path_to_route.get(candidate)
        if route:
            return route

    if compact.startswith("/docs/opik"):
        stripped = compact[len("/docs/opik") :].lstrip("/")
        for candidate in (stripped, f"{stripped}.md", f"{stripped}.mdx"):
            route = path_to_route.get(candidate)
            if route:
                return route

    return None


def split_path_and_suffix(raw_url: str) -> Tuple[str, str]:
    if "://" in raw_url:
        parsed = urlparse(raw_url)
        if parsed.path:
            suffix = ""
            if parsed.query:
                suffix += f"?{parsed.query}"
            if parsed.fragment:
                suffix += f"#{parsed.fragment}"
            return parsed.path, suffix

    split = re.match(r"([^?#]*)([?#].*)?", raw_url)
    if split:
        return split.group(1), split.group(2) or ""
    return raw_url, ""


def normalize_base_url(raw_url: str) -> Optional[str]:
    if not raw_url:
        return None

    lowered = raw_url.lower()
    if lowered.startswith(SKIP_URL_PREFIXES):
        return None

    path, suffix = split_path_and_suffix(raw_url)
    if path.startswith("./") or path.startswith("../"):
        return None

    path = path or "/"
    if path.startswith("/"):
        return path, suffix  # type: ignore[return-value]

    if "://" not in raw_url:
        return None

    parsed = urlparse(raw_url)
    if not (parsed.scheme and parsed.netloc):
        return None
    if parsed.netloc.lower() not in KNOWN_DOC_HOSTS:
        return None
    if not parsed.path.startswith("/docs/opik"):
        return None

    return parsed.path, suffix


def resolve_relative_url(
    raw_url: str,
    base_file: Path,
    docs_root: Path,
    routes: set[str],
    path_to_route: Dict[str, str],
    redirects: List[dict],
    assets: set[str],
) -> Optional[str]:
    path, suffix = split_path_and_suffix(raw_url)
    candidate_path = (base_file.parent / path).resolve()

    candidates = {candidate_path}

    if candidate_path.suffix == "":
        candidates.add(candidate_path.with_suffix(".md"))
        candidates.add(candidate_path.with_suffix(".mdx"))

    if candidate_path.is_dir():
        candidates.add(candidate_path / "index.md")
        candidates.add(candidate_path / "index.mdx")

    docs_root = docs_root.resolve()
    for candidate in candidates:
        if not candidate.exists():
            continue

        try:
            relative_path = candidate.relative_to(docs_root)
        except ValueError:
            continue

        candidate_url = f"/{relative_path.as_posix().lstrip('/')}"
        resolved = find_route(candidate_url, routes, path_to_route, redirects)
        if resolved:
            return f"{resolved}{suffix}"

        if candidate_url in assets or candidate_url.rstrip("/") in assets:
            return f"{candidate_url}{suffix}"

    return None


def resolve_url(
    raw_url: str,
    routes: set[str],
    path_to_route: Dict[str, str],
    redirects: List[dict],
    assets: set[str],
    base_file: Optional[Path] = None,
    docs_root: Optional[Path] = None,
) -> Optional[str]:
    path, _ = split_path_and_suffix(raw_url)
    if path.startswith("./") or path.startswith("../"):
        if base_file is None or docs_root is None:
            return None
        return resolve_relative_url(raw_url, base_file, docs_root, routes, path_to_route, redirects, assets)

    target = normalize_base_url(raw_url)
    if target is None:
        return None

    path, suffix = target
    compact = path.rstrip("/")
    compact = compact or "/"

    if path.endswith("/"):
        path = path.rstrip("/") or "/"

    resolved = find_route(path, routes, path_to_route, redirects)
    if resolved:
        return f"{resolved}{suffix}"

    if path in assets or compact in assets:
        return f"{path}{suffix}"

    return None


def is_relative_missing(base_file: Path, raw_url: str) -> bool:
    path, _ = split_path_and_suffix(raw_url)
    if not (path.startswith("./") or path.startswith("../")):
        return False

    candidate = (base_file.parent / path).resolve()
    return not candidate.exists()


def check_file(
    path: Path,
    routes: set[str],
    path_to_route: Dict[str, str],
    redirects: List[dict],
    assets: set[str],
    docs_root: Path,
) -> List[Issue]:
    text = path.read_text(encoding="utf-8")
    issues: List[Issue] = []

    for pattern in LINK_PATTERNS:
        for match in pattern.finditer(text):
            raw_url = match.group(1)
            line = line_number(text, match.start(1))

            if is_relative_missing(path, raw_url):
                issues.append(
                    Issue(
                        file=path,
                        line=line,
                        original=raw_url,
                        suggestion=None,
                        kind="relative_missing",
                    )
                )
                continue

            suggestion = resolve_url(
                raw_url,
                routes,
                path_to_route,
                redirects,
                assets,
                base_file=path,
                docs_root=docs_root,
            )
            if suggestion is None:
                if raw_url.startswith(("http://", "https://", "/", "./", "../")):
                    if raw_url.startswith(("http://", "https://")) and urlparse(raw_url).path.startswith("/docs/opik"):
                        issues.append(
                            Issue(
                                file=path,
                                line=line,
                                original=raw_url,
                                suggestion=None,
                                kind="route_missing",
                            )
                        )
                    elif raw_url.startswith("/"):
                        issues.append(
                            Issue(
                                file=path,
                                line=line,
                                original=raw_url,
                                suggestion=None,
                                kind="route_missing",
                            )
                        )
                    elif raw_url.startswith(("./", "../")):
                        issues.append(
                            Issue(
                                file=path,
                                line=line,
                                original=raw_url,
                                suggestion=None,
                                kind="route_missing",
                            )
                        )
                continue

            if suggestion != raw_url and raw_url.startswith(("/", "http://", "https://")):
                issues.append(
                    Issue(
                        file=path,
                        line=line,
                        original=raw_url,
                        suggestion=suggestion,
                        kind="rewrite",
                    )
                )

    return issues


def apply_fixes(
    path: Path,
    routes: set[str],
    path_to_route: Dict[str, str],
    redirects: List[dict],
    assets: set[str],
    docs_root: Optional[Path] = None,
) -> List[Issue]:
    original_text = path.read_text(encoding="utf-8")
    text = original_text
    issues: List[Issue] = []

    def rewrite(match: re.Match[str], kind: str) -> str:
        raw_url = match.group(1)
        suggestion = resolve_url(
            raw_url,
            routes,
            path_to_route,
            redirects,
            assets,
            base_file=path,
            docs_root=docs_root,
        )

        if suggestion is None:
            return match.group(0)

        full = match.group(0)
        if suggestion == raw_url:
            return full

        issues.append(
            Issue(
                file=path,
                line=line_number(text, match.start(1)),
                original=raw_url,
                suggestion=suggestion,
                kind=kind,
            )
        )
        return full.replace(raw_url, suggestion, 1)

    for pattern in LINK_PATTERNS:
        def _repl(match: re.Match[str]) -> str:
            return rewrite(match, "rewrite")

        text = pattern.sub(_repl, text)

    if text != original_text:
        path.write_text(text, encoding="utf-8")

    return issues


def summarize(issues: List[Issue]) -> None:
    by_kind: Dict[str, int] = {}
    for issue in issues:
        by_kind[issue.kind] = by_kind.get(issue.kind, 0) + 1

    for kind, count in sorted(by_kind.items()):
        print(f"{kind}: {count}")

    if not issues:
        return

    print("\nTop issues:")
    for issue in issues[:200]:
        suggestion = issue.suggestion or "NO_SUGGESTION"
        print(f"{issue.file}:{issue.line}: {issue.original} -> {suggestion} ({issue.kind})")


def build_parser() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Fern docs links")
    parser.add_argument(
        "--docs-root",
        type=Path,
        default=DEFAULT_DOCS_ROOT,
        help="Path to fern/docs folder",
    )
    parser.add_argument(
        "--docs-yml",
        type=Path,
        default=DEFAULT_DOCS_YML,
        help="Path to fern/docs.yml",
    )
    parser.add_argument(
        "--fix",
        action="store_true",
        help="Rewrite canonical links in place",
    )
    parser.add_argument(
        "--check-only",
        action="store_true",
        help="Exit with non-zero if any issues are found",
    )
    parser.add_argument(
        "--json-output",
        type=Path,
        default=None,
        help="Optional path to write a JSON issue report",
    )
    return parser.parse_args()


def main() -> int:
    args = build_parser()

    config = load_docs_config(args.docs_yml)
    routes, path_to_route, redirects = collect_routes(config)
    assets = collect_img_routes(args.docs_root)

    mdx_files = [
        path
        for path in args.docs_root.rglob("*")
        if path.is_file() and path.suffix.lower() in {".md", ".mdx"}
    ]

    issues: List[Issue] = []

    if args.fix:
        for path in mdx_files:
            issues.extend(apply_fixes(path, routes, path_to_route, redirects, assets, args.docs_root))
    else:
        for path in mdx_files:
            issues.extend(check_file(path, routes, path_to_route, redirects, assets, args.docs_root))

    if args.json_output:
        payload = {
            "route_missing": [i for i in issues if i.kind != "relative_missing"],
            "relative_missing": [i for i in issues if i.kind == "relative_missing"],
            "all": issues,
        }
        args.json_output.write_text(
            __import__("json").dumps(
                [
                    {
                        "file": str(item.file),
                        "line": item.line,
                        "original": item.original,
                        "suggestion": item.suggestion,
                        "kind": item.kind,
                    }
                    for item in payload["all"]
                ],
                indent=2,
            ),
            encoding="utf-8",
        )

    if args.check_only:
        if issues:
            print(f"FAILED: {len(issues)} docs link issues found", file=sys.stderr)
            summarize(issues)
            return 1
        print("OK: no docs link issues found")
        return 0

    if args.fix:
        if issues:
            summarize(issues)
            return 0
        return 0

    summarize(issues)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
