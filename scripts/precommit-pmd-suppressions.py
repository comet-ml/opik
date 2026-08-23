#!/usr/bin/env python3
"""Require a stated reason on every InlineFullyQualifiedName suppression.

PMD accepts a bare `// NOPMD` and a bare
`@SuppressWarnings("PMD.InlineFullyQualifiedName")` with no rationale, and offers
no option to require one. CONTRIBUTING.md and the opik-backend skill both require
a reason, so the gate is enforced here.

Input is PMD's own XML report (run with --show-suppressed), not the Java source:
PMD reports one <suppressedviolation> per suppression it actually honoured for a
rule, which means

  * only suppressions of THIS rule are policed — a bare `// NOPMD` silencing some
    other PMD rule is none of this check's business, and
  * the reason text for `// NOPMD - <reason>` arrives already parsed, in usermsg.

`@suppresswarnings` suppressions always carry an empty usermsg (the annotation has
nowhere to put prose), so for those the reason must be an adjacent comment that
names the rule or the NOPMD marker explicitly. Requiring the marker — rather than
accepting any nearby comment — is what keeps unrelated Javadoc and code that
merely contains `*/` from passing as justification.

Accepted — every form below is covered by a regression test in
scripts/test_precommit_wrappers.sh, so this list cannot drift from the behaviour:

    java.sql.Date d; // NOPMD - collides with the imported java.util.Date

    // NOPMD - collides with the imported java.util.Date
    @SuppressWarnings("PMD.InlineFullyQualifiedName")
    java.sql.Date d;

    @SuppressWarnings("PMD.InlineFullyQualifiedName") // NOPMD - collides with ...
    java.sql.Date d;

Rejected: bare `// NOPMD`, `// NOPMD -`, a bare annotation, and any annotation
whose only nearby comment is unrelated prose (Javadoc, or a code line that merely
contains a comment marker) rather than an explicit suppression note.

The marker comment must be on the annotation's own line or the line directly
above it — a comment *below* the annotation is not searched.

Usage: precommit-pmd-suppressions.py <pmd-report.xml>
Exits 1 with one diagnostic per offending suppression; 0 when clean. Unreadable
or malformed input is a failure, never a silent pass.
"""

import os
import re
import subprocess
import sys
import xml.etree.ElementTree as ET

RULE = "InlineFullyQualifiedName"
NS = {"pmd": "http://pmd.sourceforge.net/report/2.0.0"}

# <suppressedviolation> carries no rule name — only filename, suppressiontype,
# msg and usermsg. Scoping to this rule therefore relies on the ruleset holding
# exactly one rule, so every suppression PMD reports for it is ours: a `// NOPMD`
# silencing some other PMD rule never appears in this report at all. The message
# prefix is asserted as a guard, so adding a second rule to the ruleset surfaces
# here instead of silently widening what this check polices.
MSG_PREFIX = "Inline fully-qualified name"

# The reason must be attached to an explicit suppression marker, so a stray
# comment or Javadoc line can never be mistaken for a justification.
MARKER = re.compile(rf"(?://\s*NOPMD|{RULE})\b[ \t]*[-:]?[ \t]*(?P<reason>.*)$")

# At least two consecutive word characters — rejects "", "-", "//", "...".
WORDS = re.compile(r"\w{2,}")


def has_reason(text: str) -> bool:
    return bool(WORDS.search(text.replace("*/", " ")))


def changed_lines(path: str) -> set[int] | None:
    """Line numbers of `path` added/modified versus the merge-base, or None.

    None means "couldn't determine" — not in a repo, git missing, file untracked
    — and callers then validate every annotation rather than skipping the check.

    PMD reports no line for @SuppressWarnings suppressions, so without this the
    whole file is validated and a bare annotation predating the hook would fail
    every later edit to that file. The gate is going-forward-only, so only
    suppressions the current change actually touches are its business.
    """
    # Test seam: supply the scope directly instead of shelling out to git. Keeps
    # the scoping tests free of a fixture repo — a nested one is fragile enough
    # that it twice leaked commits into the surrounding repository. Values are
    # "L1,L2,…" for a known scope, or "unknown" for the can't-determine path.
    override = os.environ.get("PMD_SUPPRESSION_CHANGED_LINES")
    if override is not None:
        if override.strip().lower() == "unknown":
            return None
        return {int(n) for n in override.replace(",", " ").split()}

    base = os.environ.get("PMD_SUPPRESSION_DIFF_BASE", "")
    if base:
        # CI passes the PR/push base, so the scope is the whole branch.
        revs = [f"{base}...HEAD"]
    else:
        # Locally the scope is what this commit will contain: staged changes,
        # plus unstaged edits so a bare `pre-commit run --files` behaves the same.
        revs = ["HEAD"]

    try:
        out = subprocess.run(
            ["git", "diff", "--unified=0", "--no-color", *revs, "--", path],
            capture_output=True,
            text=True,
            check=False,
        )
    except (OSError, ValueError):
        return None
    if out.returncode != 0:
        # Not a repo, no such rev, untracked file — can't scope, so validate all.
        return None

    lines: set[int] = set()
    for hunk in re.finditer(r"^@@ -\S+ \+(\d+)(?:,(\d+))? @@", out.stdout, re.M):
        start = int(hunk.group(1))
        count = int(hunk.group(2) or 1)
        lines.update(range(start, start + count))

    # An empty diff means this file carries no changes relative to the scope, so
    # none of its suppressions belong to the current change. In CI that scope is
    # the PR base (see PMD_SUPPRESSION_DIFF_BASE, set by the Code Quality
    # workflow), which is what keeps a newly added bare annotation in scope there
    # — without the base ref, CI's clean merge-commit checkout would diff to
    # nothing and the gate would miss it.
    return lines


def annotation_reason(path: str, hint: str) -> bool:
    """True when an explicit suppression note sits next to the annotation.

    PMD does not report a line for @SuppressWarnings suppressions, so the file is
    scanned for annotations naming this rule. Only annotations on lines the
    current change touches are validated (see changed_lines) — a pre-existing
    bare annotation is left alone until someone edits it.
    """
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    except OSError as exc:
        print(f"{path}: cannot read file to validate {hint}: {exc}", file=sys.stderr)
        return False

    touched = changed_lines(path)
    ok = True
    for i, line in enumerate(lines):
        if RULE not in line or "@SuppressWarnings" not in line:
            continue
        # A suppression spans its marker comment (line above), the annotation
        # itself, and the declaration it applies to (line below). Touching any of
        # the three makes it part of the current change and therefore in scope.
        if touched is not None and not ({i, i + 1, i + 2} & touched):
            continue
        candidates = [line]
        if i > 0:
            candidates.append(lines[i - 1])
        if not any(
            (m := MARKER.search(c)) and has_reason(m.group("reason"))
            for c in candidates
        ):
            print(
                f'{path}:{i + 1}: @SuppressWarnings("PMD.{RULE}") needs a reason — '
                'add `// NOPMD - <why>` on this line or directly above it',
                file=sys.stderr,
            )
            ok = False
    return ok


def main(argv: list[str]) -> int:
    if len(argv) != 1:
        print(f"usage: {sys.argv[0]} <pmd-report.xml>", file=sys.stderr)
        return 1

    try:
        root = ET.parse(argv[0]).getroot()
    except (OSError, ET.ParseError) as exc:
        # Fail closed: an unreadable report means the suppressions were never
        # checked, which must not look like success.
        print(f"cannot read PMD report {argv[0]}: {exc}", file=sys.stderr)
        return 1

    ok = True
    annotated_files = set()

    for sv in root.findall("pmd:suppressedviolation", NS):
        if not (sv.get("msg") or "").startswith(MSG_PREFIX):
            continue
        path = sv.get("filename") or "<unknown>"
        kind = (sv.get("suppressiontype") or "").lower()
        usermsg = sv.get("usermsg") or ""

        if kind == "@suppresswarnings":
            # usermsg is always empty for annotations; validate in the source.
            annotated_files.add(path)
        elif not has_reason(usermsg):
            print(
                f"{path}: bare `// NOPMD` suppressing {RULE} — add a reason, e.g. "
                "`// NOPMD - collides with the imported java.util.Date`",
                file=sys.stderr,
            )
            ok = False

    for path in sorted(annotated_files):
        if not annotation_reason(path, f"@SuppressWarnings(\"PMD.{RULE}\")"):
            ok = False

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
