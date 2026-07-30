#!/usr/bin/env python3
"""Reconcile taxonomy.yaml's derived fields with the actual spec tags.

The tags are the source of truth. A capability is covered when some spec carries
its `@cap:`/`@vcap:` tag; the `covered:` flag in taxonomy.yaml is a cache of that
fact, and this job refreshes the cache. Nobody edits those flags by hand.

  covered:   derived — does any spec tag this capability
  tier:      derived — the shallowest tier among the tagging tests
  (everything else)  authored — areas, capability keys, notes, cloud_only,
                     state, axes. Never touched here. Those change via the
                     discovery job (OPIK-7632) or a human PR.

Run nightly after merges land. Exits 0 with no changes when the file already
agrees, so it is cheap to run on every push.

Reads tags via `playwright test --list --reporter=json`, not a regex, so it needs
`npm ci` in e2e/ and visual-tests/ first. That is deliberate: tags union from
describe to test, so the tier covering a given `@cap:` is only knowable after
inheritance is resolved. A regex version of this job was measured "correcting"
5 accurate `tier:` fields to wrong values.

Why line surgery instead of yaml.safe_load + dump: the taxonomy carries 175
comments and 242 column-aligned flow mappings that a load/dump round-trip
flattens. That would produce a ~700-line diff every night and make the PRs
unreviewable. Here every edit rewrites values *inside* one existing line, so the
nightly diff is exactly the capabilities whose coverage actually moved.

Usage:
  reconcile.py --taxonomy <t.yaml> --estate <tests_end_to_end>          # apply
  reconcile.py ... --check                    # exit 1 if drifted, write nothing
  reconcile.py ... --summary                  # human-readable change list
"""

from __future__ import annotations

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml required: pip install pyyaml")

TAG_BLOCK = re.compile(r"tag:\s*\[(.*?)\]", re.S)
TAG_LITERAL = re.compile(r"['\"](@[^'\"]+)['\"]")

# Shallowest tier wins: if a capability is exercised by both a t1 smoke test and
# a t3 nightly, the honest answer to "how often is this verified" is t1.
TIER_ORDER = ("t1-smoke", "t2-cuj", "t3-nightly")

# `  key:   { covered: true,  tier: t1-smoke }` — captures indent, key, and the
# inside of the braces so we can rewrite values without touching alignment.
FLOW_ENTRY = re.compile(r"^(?P<indent>\s+)(?P<key>[\w.-]+):(?P<pad>\s*)\{(?P<body>[^}]*)\}\s*$")
SECTION = re.compile(r"^(?P<indent>\s+)(?P<name>[\w.-]+):\s*$")


# --------------------------------------------------------------------------- #
# read the estate
# --------------------------------------------------------------------------- #

def scan_estate(estate: Path) -> tuple[dict[str, set[str]], dict[str, set[str]]]:
    """-> (functional cap -> tiers covering it, visual cap -> tiers).

    Asks Playwright, not a regex. Tags union from describe to test, so the tier
    that applies to a given `@cap:` is only knowable after describe-inheritance
    is resolved — and a file may hold several describes at different tiers.
    A whole-file regex scan gets this wrong in both directions: it was measured
    attributing t1 to t3-only capabilities in `ollie-agentic.spec.ts` and
    `dataset-items.spec.ts`, which would have made the nightly job "correct" 5
    accurate fields to wrong values. `--list --reporter=json` is the same
    resolution Playwright uses to select tests, so it is the ground truth.
    """
    caps: dict[str, set[str]] = {}
    vcaps: dict[str, set[str]] = {}

    for sub, sink, prefix in (
        ("e2e", caps, "@cap:"),
        ("visual-tests", vcaps, "@vcap:"),
    ):
        root = estate / sub
        if not (root / "package.json").is_file():
            continue
        for tags in playwright_test_tags(root):
            tiers = {t.lstrip("@") for t in tags if t.lstrip("@") in TIER_ORDER}
            for t in tags:
                if t.startswith(prefix):
                    sink.setdefault(t.split(":", 1)[1], set()).update(tiers)
    return caps, vcaps


def playwright_test_tags(project_root: Path) -> list[set[str]]:
    """Every test's fully-resolved tag set, via `playwright test --list`."""
    proc = subprocess.run(
        ["npx", "playwright", "test", "--list", "--reporter=json"],
        cwd=project_root, capture_output=True, text=True, timeout=300,
    )
    # Playwright writes the JSON report to stdout even when it also warns on
    # stderr; only a missing/!0-with-empty-stdout run is a real failure.
    if not proc.stdout.strip():
        raise RuntimeError(
            f"playwright --list produced no output in {project_root}\n{proc.stderr[-800:]}"
        )
    report = json.loads(proc.stdout)

    out: list[set[str]] = []

    def walk(suite: dict) -> None:
        for spec in suite.get("specs") or []:
            for test in spec.get("tests") or []:
                tags = set(test.get("tags") or [])
                # Older reporter shapes hang tags off the spec, not the test.
                tags.update(spec.get("tags") or [])
                # Normalise: the reporter may or may not keep the leading '@'.
                out.append({t if t.startswith("@") else f"@{t}" for t in tags})
        for child in suite.get("suites") or []:
            walk(child)

    for suite in report.get("suites") or []:
        walk(suite)
    return out


def shallowest(tiers: set[str]) -> str | None:
    for t in TIER_ORDER:
        if t in tiers:
            return t
    return None


# --------------------------------------------------------------------------- #
# rewrite one flow-mapping body
# --------------------------------------------------------------------------- #

def parse_body(body: str) -> list[tuple[str, str]]:
    """`covered: true,  tier: t1-smoke` -> [('covered','true'), ('tier','t1-smoke')].

    Order-preserving and splits only on top-level commas, so a quoted note
    containing a comma survives.
    """
    out, depth, cur = [], 0, ""
    for ch in body:
        if ch in "\"'":
            depth ^= 1
        if ch == "," and not depth:
            out.append(cur)
            cur = ""
        else:
            cur += ch
    out.append(cur)
    pairs = []
    for chunk in out:
        if ":" not in chunk:
            continue
        k, v = chunk.split(":", 1)
        pairs.append((k.strip(), v.strip()))
    return pairs


def render_body(pairs: list[tuple[str, str]]) -> str:
    """Re-emit `k: v` pairs in the file's house style.

    `covered: true,  tier: t1-smoke` uses two spaces so the tier column lines up
    with neighbouring entries; a trailing `note:` uses one. Matching this keeps
    an unrelated capability from showing up as whitespace noise in the diff.
    """
    parts = []
    for i, (k, v) in enumerate(pairs):
        if i:
            parts.append(", " if k == "note" else ",  ")
        parts.append(f"{k}: {v}")
    return "".join(parts)


def set_field(pairs: list[tuple[str, str]], key: str, value: str | None) -> list[tuple[str, str]]:
    """Set, insert (right after `covered`), or drop a field, preserving order."""
    out = [(k, v) for k, v in pairs if k != key]
    if value is None:
        return out
    if any(k == key for k, _ in pairs):
        return [(k, value if k == key else v) for k, v in pairs]
    idx = next((i for i, (k, _) in enumerate(out) if k == "covered"), -1)
    out.insert(idx + 1, (key, value))
    return out


# --------------------------------------------------------------------------- #
# reconcile
# --------------------------------------------------------------------------- #

class Change:
    __slots__ = ("line", "area", "cap", "kind", "before", "after")

    def __init__(self, line, area, cap, kind, before, after):
        self.line, self.area, self.cap = line, area, cap
        self.kind, self.before, self.after = kind, before, after

    def __str__(self) -> str:
        return f"  {self.area}.{self.cap}: {self.kind} {self.before} -> {self.after}"


def reconcile(taxonomy: Path, estate: Path) -> tuple[list[str], list[Change], list[str]]:
    """-> (new lines, changes, warnings). Never writes."""
    tax = yaml.safe_load(taxonomy.read_text())
    caps, vcaps = scan_estate(estate)

    # The load dimension is `status: planned` and reports to JUnit, not Allure.
    # Its specs are also outside the agreed estate, so it has no tags to read —
    # leaving it alone is correct, not an omission.
    dims = tax.get("dimensions") or {}
    skip_load = (dims.get("load") or {}).get("status") != "active"

    lines = taxonomy.read_text().splitlines()
    changes: list[Change] = []
    warnings: list[str] = []

    # Track where we are: which area, and which block within it.
    area: str | None = None
    block: str | None = None
    area_indent = 0

    known_areas = set((tax.get("areas") or {}).keys())
    seen: dict[str, set[str]] = {"capabilities": set(), "visual": set()}

    for i, raw in enumerate(lines):
        m_sec = SECTION.match(raw)
        if m_sec:
            name, indent = m_sec.group("name"), len(m_sec.group("indent"))
            if name in known_areas and indent <= 4:
                area, block, area_indent = name, None, indent
                continue
            if area and name in ("capabilities", "visual", "load") and indent > area_indent:
                block = name
                continue
            # A nested key inside a block-style entry (load axes) — not a section.
            if block and indent > area_indent + 2:
                continue
            if indent <= area_indent and name not in ("capabilities", "visual", "load"):
                block = None
            continue

        if not area or block not in ("capabilities", "visual"):
            continue
        if block == "load" and skip_load:
            continue

        m = FLOW_ENTRY.match(raw)
        if not m:
            continue

        cap = m.group("key")
        pairs = parse_body(m.group("body"))
        fq = f"{area}.{cap}"
        seen[block].add(fq)

        if block == "capabilities":
            tiers = caps.get(fq)
        else:
            tiers = vcaps.get(fq)

        is_covered = tiers is not None
        declared = dict(pairs).get("covered", "false").strip().lower() == "true"

        new_pairs = pairs
        if declared != is_covered:
            new_pairs = set_field(new_pairs, "covered", "true" if is_covered else "false")
            changes.append(Change(i + 1, area, cap, "covered", declared, is_covered))

        # tier is meaningful only for functional caps, and only when covered.
        if block == "capabilities":
            want = shallowest(tiers) if tiers else None
            have = dict(pairs).get("tier")
            if want != have:
                new_pairs = set_field(new_pairs, "tier", want)
                changes.append(Change(i + 1, area, cap, "tier", have or "-", want or "-"))

        if new_pairs is not pairs:
            lines[i] = f"{m.group('indent')}{cap}:{m.group('pad')}{{ {render_body(new_pairs)} }}"

    # A tag pointing at a capability the taxonomy doesn't have. tag_lint blocks
    # this on PRs, so reaching here means the taxonomy was edited to remove a
    # capability that specs still tag. Report, never invent an entry: the
    # denominator is authored, and silently growing it would let a typo'd tag
    # mint its own capability.
    for fq in sorted(set(caps) - seen["capabilities"]):
        warnings.append(f"@cap:{fq} is tagged in a spec but absent from the taxonomy")
    for fq in sorted(set(vcaps) - seen["visual"]):
        warnings.append(f"@vcap:{fq} is tagged in a spec but absent from the taxonomy")

    return lines, changes, warnings


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--taxonomy", required=True, type=Path)
    ap.add_argument("--estate", required=True, type=Path)
    ap.add_argument("--check", action="store_true", help="exit 1 if drifted; write nothing")
    ap.add_argument("--summary", action="store_true", help="print the change list")
    args = ap.parse_args()

    lines, changes, warnings = reconcile(args.taxonomy, args.estate)

    for w in warnings:
        print(f"warning: {w}", file=sys.stderr)

    if args.summary or args.check:
        if changes:
            print(f"{len(changes)} derived field(s) drifted from the tags:")
            for c in changes:
                print(c)
        else:
            print("taxonomy already agrees with the spec tags — nothing to do.")

    if args.check:
        return 1 if (changes or warnings) else 0

    if changes:
        args.taxonomy.write_text("\n".join(lines) + "\n")
        print(f"reconcile: updated {args.taxonomy} ({len(changes)} field(s))")
    else:
        print("reconcile: no changes")

    # An orphan tag is not drift this job can repair — the capability it names
    # was removed from the authored denominator while specs still claim it, so
    # coverage is understated until a human decides whether the capability or the
    # tag is wrong. tag_lint blocks this on PRs; reaching here means it arrived
    # some other way, and it must not pass silently.
    return 1 if warnings else 0


if __name__ == "__main__":
    sys.exit(main())
