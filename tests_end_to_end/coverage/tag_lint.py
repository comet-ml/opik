#!/usr/bin/env python3
"""Tag lint for the Opik e2e + visual test estate.

Enforces the tag grammar in TESTING-TAGS.md against a taxonomy YAML:

  1. tier_and_area_required     every non-exempt e2e spec declares a tier tag
                                and exactly one @area: tag. A spec carrying a
                                valid suite selector (@provider-sanity,
                                @release-gate[:version], @t1-stsaas) may be
                                tier-less — it runs on its own cadence, outside
                                the t1/t2/t3 ladder. Tier *cardinality* is not
                                enforced; see the note in lint_spec().
  2. cap_required               every non-visual spec declares at least one
                                @cap:, and every visual spec at least one @vcap:
  3. tags_must_exist_in_taxonomy @area:/@cap:/@vcap: values resolve in taxonomy,
                                and a @cap: sits under the spec's declared area
  4. visual_state_enum          every visual capability in taxonomy.yaml declares
                                a `state:` from the dimensions.visual.states enum
                                (this checks the taxonomy, not the spec files)

Exits non-zero on any violation. Read-only; never edits specs.

Usage:
  tag_lint.py --taxonomy taxonomy.yaml --estate /path/to/opik/tests_end_to_end
  tag_lint.py ... --format github     # emit ::error:: annotations for CI

Why regex and not the TS AST: the tags we lint are always string literals inside
a `tag: [...]` array, which is reliably greppable. Test *titles* are not (3 specs
pass a variable, 5 use template literals) — but titles are not linted here. The
coverage builder resolves runtime titles via `playwright test --list` instead.
"""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

try:
    import yaml
except ImportError:
    sys.exit("pyyaml required: pip install pyyaml")

TIERS = {"@t1-smoke", "@t2-cuj", "@t3-nightly"}
# Selectors are orthogonal to tier — they say WHERE a test runs, not how deep.
# A spec may carry any number of these, including none.
SUITES = {"@t1-stsaas", "@provider-sanity", "@release-gate"}
RELEASE_GATE_VERSIONED = re.compile(r"^@release-gate:[\w.\-]+$")

# Any `tag: [ ... ]` array, single or multi-line.
TAG_BLOCK = re.compile(r"tag:\s*\[(.*?)\]", re.S)
TAG_LITERAL = re.compile(r"['\"](@[^'\"]+)['\"]")


class Finding:
    __slots__ = ("path", "line", "msg")

    def __init__(self, path: str, line: int, msg: str):
        self.path, self.line, self.msg = path, line, msg

    def render(self, fmt: str) -> str:
        if fmt == "github":
            return f"::error file={self.path},line={self.line}::{self.msg}"
        return f"{self.path}:{self.line}: {self.msg}"


def load_taxonomy(p: Path) -> dict:
    with p.open() as fh:
        return yaml.safe_load(fh)


def build_index(tax: dict) -> tuple[set[str], set[str], set[str], set[str], dict[str, str]]:
    """-> (areas, functional caps, visual caps, visual states, alias -> canonical)."""
    areas, caps, vcaps = set(), set(), set()
    canonical: dict[str, str] = {}
    for area, body in (tax.get("areas") or {}).items():
        areas.add(area)
        for alias in body.get("tag_aliases") or []:
            areas.add(alias)
            # Remember the real name so lint messages suggest the canonical area,
            # not the deprecated alias the spec already has.
            canonical[alias] = area
        for cap in (body.get("capabilities") or {}):
            caps.add(f"{area}.{cap}")
        for vcap in (body.get("visual") or {}):
            vcaps.add(f"{area}.{vcap}")
    states = set((tax.get("dimensions", {}).get("visual", {}) or {}).get("states") or [])
    return areas, caps, vcaps, states, canonical


def tags_in(text: str) -> list[tuple[int, str]]:
    """Every tag literal with the 1-indexed line of its enclosing tag: array."""
    out = []
    for block in TAG_BLOCK.finditer(text):
        line = text.count("\n", 0, block.start()) + 1
        for lit in TAG_LITERAL.finditer(block.group(1)):
            out.append((line, lit.group(1)))
    return out


def is_valid_suite(tag: str) -> bool:
    return tag in SUITES or bool(RELEASE_GATE_VERSIONED.match(tag))


def lint_spec(path: Path, rel: str, idx, retired: dict, *, visual: bool) -> list[Finding]:
    areas, caps, vcaps, _, canonical = idx
    text = path.read_text(encoding="utf-8")
    found = tags_in(text)
    f: list[Finding] = []

    tiers = [(l, t) for l, t in found if t in TIERS]
    area_tags = [(l, t) for l, t in found if t.startswith("@area:")]
    cap_tags = [(l, t) for l, t in found if t.startswith("@cap:")]
    vcap_tags = [(l, t) for l, t in found if t.startswith("@vcap:")]

    if visual:
        # Visual specs are asserted per screenshot; they carry @vcap: and no tier.
        if not vcap_tags:
            f.append(Finding(rel, 1, "visual spec declares no @vcap: tag"))
        for line, t in tiers:
            f.append(Finding(rel, line, f"visual spec must not carry a tier tag ({t})"))
    else:
        suites_present = [(l, t) for l, t in found if is_valid_suite(t)]
        if not tiers and not suites_present:
            f.append(Finding(rel, 1, "no tier tag: expected exactly one of @t1-smoke / @t2-cuj / @t3-nightly"))
        # A spec carrying only a selector suite (e.g. @provider-sanity) is
        # legitimately tier-less: it runs on its own cadence, deliberately
        # outside the t1/t2/t3 ladder. Documented in playground-providers.spec.ts.
        #
        # Tier cardinality is deliberately NOT enforced. "Exactly one tier"
        # constrains a single test, but tags union from describe to test, so the
        # scope that must hold the invariant is a test *after* inheritance — not
        # a file, and not a `tag: [...]` block either (a describe and its tests
        # are separate blocks whose tags combine). Specs legitimately carry
        # several tiers across sibling describes, so any file- or block-level
        # count would fail valid specs. Enforcing it needs the TS AST; the hole
        # is left open on purpose rather than closed wrongly.
        if not cap_tags:
            f.append(Finding(rel, 1, "no @cap: tag: a spec must declare every capability it asserts"))
        if not area_tags:
            f.append(Finding(rel, 1, "no @area: tag"))
        elif len({t for _, t in area_tags}) > 1:
            names = ", ".join(sorted({t for _, t in area_tags}))
            f.append(Finding(rel, area_tags[0][0], f"multiple @area: tags ({names}); a spec belongs to exactly one area"))

    for line, t in area_tags:
        if t.split(":", 1)[1] not in areas:
            f.append(Finding(rel, line, f"unknown area '{t}' — not in taxonomy"))
    for line, t in cap_tags:
        if t.split(":", 1)[1] not in caps:
            f.append(Finding(rel, line, f"unknown capability '{t}' — not in taxonomy"))
    for line, t in vcap_tags:
        if t.split(":", 1)[1] not in vcaps:
            f.append(Finding(rel, line, f"unknown visual capability '{t}' — not in taxonomy"))

    # A @cap: must sit under the spec's declared area.
    if len({t for _, t in area_tags}) == 1:
        area = area_tags[0][1].split(":", 1)[1]
        for line, t in cap_tags:
            val = t.split(":", 1)[1]
            if "." in val and val.split(".", 1)[0] != area:
                f.append(Finding(rel, line, f"'{t}' does not belong to declared area '@area:{area}'"))

    for line, t in found:
        if t in TIERS or t.startswith(("@area:", "@cap:", "@vcap:")) or is_valid_suite(t):
            continue
        # Legacy bare area tags (@datasets, @prompts, ...) predate the @area:
        # prefix. Name the migration explicitly rather than calling them junk.
        bare = t.lstrip("@")
        if bare in areas:
            target = canonical.get(bare, bare)
            f.append(Finding(rel, line, f"legacy bare area tag '{t}' — replace with '@area:{target}'"))
        elif bare in retired:
            info = retired[bare]
            opts = " or ".join(f"@area:{a}" for a in info.get("replaced_by") or [])
            f.append(Finding(rel, line, f"retired tag '{t}' ({info.get('reason')}) — use {opts}"))
        else:
            f.append(Finding(rel, line, f"unrecognised tag '{t}' — not a tier, suite selector, or taxonomy tag"))

    return f


def lint_taxonomy(tax: dict, idx) -> list[Finding]:
    """Rule 3: visual state values must be in the enum."""
    _, _, _, states, _ = idx
    out = []
    for area, body in (tax.get("areas") or {}).items():
        for vcap, spec in (body.get("visual") or {}).items():
            st = (spec or {}).get("state")
            if st is None:
                out.append(Finding("taxonomy.yaml", 1, f"{area}.{vcap}: visual capability has no `state:`"))
            elif st not in states:
                allowed = ", ".join(sorted(states))
                out.append(Finding("taxonomy.yaml", 1, f"{area}.{vcap}: state '{st}' not in [{allowed}]"))
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--taxonomy", required=True, type=Path)
    ap.add_argument("--estate", required=True, type=Path, help="path to tests_end_to_end/")
    ap.add_argument("--format", choices=["text", "github"], default="text")
    args = ap.parse_args()

    tax = load_taxonomy(args.taxonomy)
    idx = build_index(tax)
    exempt = set((tax.get("rules") or {}).get("exempt_dirs") or [])
    retired = tax.get("retired_tags") or {}

    e2e = sorted((args.estate / "e2e" / "tests").rglob("*.spec.ts"))
    vis = sorted((args.estate / "visual-tests" / "tests").rglob("*.spec.ts"))

    findings = lint_taxonomy(tax, idx)
    checked = skipped = 0

    for p in e2e:
        rel = str(p.relative_to(args.estate.parent))
        if any(part in exempt for part in p.parts):
            skipped += 1
            continue
        checked += 1
        findings += lint_spec(p, rel, idx, retired, visual=False)

    for p in vis:
        rel = str(p.relative_to(args.estate.parent))
        checked += 1
        findings += lint_spec(p, rel, idx, retired, visual=True)

    for f in findings:
        print(f.render(args.format))

    print(f"\ntag-lint: {checked} specs checked, {skipped} exempt, {len(findings)} problem(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main())
