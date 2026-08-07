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

import re
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


def annotation_reason(path: str, hint: str) -> bool:
    """True when an explicit suppression note sits next to the annotation.

    PMD does not report a line for @SuppressWarnings suppressions, so the whole
    file is scanned for annotations naming this rule; each must have a marker
    comment carrying prose on its own line or on the line above.
    """
    try:
        with open(path, encoding="utf-8") as fh:
            lines = fh.read().splitlines()
    except OSError as exc:
        print(f"{path}: cannot read file to validate {hint}: {exc}", file=sys.stderr)
        return False

    ok = True
    for i, line in enumerate(lines):
        if RULE not in line or "@SuppressWarnings" not in line:
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
