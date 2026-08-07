#!/usr/bin/env python3
"""Reject suppressions of InlineFullyQualifiedName that don't state a reason.

PMD accepts a bare `// NOPMD` and a bare
`@SuppressWarnings("PMD.InlineFullyQualifiedName")` with no rationale, and offers
no option to require one. CONTRIBUTING.md and the opik-backend skill both require
a reason, so the gate is enforced here.

Accepted rationale forms:
  java.sql.Date d;                        // NOPMD - collides with java.util.Date
  // collides with java.util.Date
  @SuppressWarnings("PMD.InlineFullyQualifiedName")
  /* collides with java.util.Date */
  @SuppressWarnings("PMD.InlineFullyQualifiedName")
  @SuppressWarnings("PMD.InlineFullyQualifiedName") // collides with ...

Rejected: no comment at all, an empty or punctuation-only comment, and a
preceding line that merely happens to contain `*` (e.g. `int x = 2 * 3;`) or is
unrelated Javadoc.

Exits 1 and prints one diagnostic per offending site; exits 0 when clean.
"""

import re
import sys

RULE = "PMD.InlineFullyQualifiedName"

# `// NOPMD` optionally followed by a `-`/`:` separator, then the rationale.
NOPMD = re.compile(r"//\s*NOPMD\b[ \t]*[-:]?[ \t]*(?P<reason>.*)$")

# Annotation start; the value may span lines, so only the opening is matched here.
SUPPRESS_START = re.compile(r"@SuppressWarnings\s*\(")

# A comment carries a reason only if it holds at least two consecutive word
# characters — enough to reject `//`, `// -`, `/* */` and `// ...` while
# accepting any real prose.
WORDS = re.compile(r"\w{2,}")


def strip_comment_markers(text: str) -> str:
    text = text.strip()
    for prefix in ("///", "//", "/**", "/*", "*/", "*"):
        if text.startswith(prefix):
            text = text[len(prefix) :]
            break
    return text.replace("*/", "").strip()


def has_reason(text: str) -> bool:
    return bool(WORDS.search(strip_comment_markers(text)))


def is_comment_line(text: str) -> bool:
    """True when the line is *only* a comment — not code that contains `*`."""
    stripped = text.strip()
    return stripped.startswith(("//", "/*", "*")) or stripped.endswith("*/")


def annotation_span(lines: list[str], start: int) -> int:
    """Return the index of the line closing the annotation that opens at `start`.

    The value can span lines (`@SuppressWarnings({\n "PMD.X"})`), so parens are
    balanced from the opening one rather than assuming a single line.
    """
    depth = 0
    for i in range(start, len(lines)):
        for ch in lines[i]:
            if ch == "(":
                depth += 1
            elif ch == ")":
                depth -= 1
                if depth == 0:
                    return i
    return start


def preceding_reason(lines: list[str], start: int) -> bool:
    """Look upward from the annotation for a comment block carrying a reason.

    Only contiguous comment-only lines are considered, so unrelated code above
    the annotation is never mistaken for rationale. Any comment in the block
    with real prose satisfies the requirement; a Javadoc block that says nothing
    (or says nothing but punctuation) does not.
    """
    i = start - 1
    while i >= 0 and is_comment_line(lines[i]):
        if has_reason(lines[i]):
            return True
        i -= 1
    return False


def check(path: str) -> list[str]:
    try:
        with open(path, encoding="utf-8", errors="replace") as fh:
            lines = fh.read().splitlines()
    except OSError:
        return []

    problems = []
    i = 0
    while i < len(lines):
        line = lines[i]

        match = NOPMD.search(line)
        if match and not has_reason(match.group("reason")):
            problems.append(
                f"{path}:{i + 1}: bare `// NOPMD` — add a reason, e.g. "
                "`// NOPMD - collides with the imported java.util.Date`"
            )

        if SUPPRESS_START.search(line):
            end = annotation_span(lines, i)
            block = "\n".join(lines[i : end + 1])
            if RULE in block:
                # A trailing comment on any line of the annotation counts, as
                # does a contiguous comment block directly above it.
                trailing = any(
                    (m := NOPMD.search(ln)) and has_reason(m.group("reason"))
                    or ("//" in ln and has_reason(ln.split("//", 1)[1]))
                    for ln in lines[i : end + 1]
                )
                if not trailing and not preceding_reason(lines, i):
                    problems.append(
                        f'{path}:{i + 1}: bare @SuppressWarnings("{RULE}") — add a '
                        "reason as a comment on this line or directly above it"
                    )
            i = end + 1
            continue
        i += 1

    return problems


def main(argv: list[str]) -> int:
    problems = [p for path in argv for p in check(path)]
    for p in problems:
        print(p, file=sys.stderr)
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
