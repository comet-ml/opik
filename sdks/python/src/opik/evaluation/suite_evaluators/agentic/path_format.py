"""Render jq-style paths into the dot-bracket form `scan` consumes.

Used by both the compression-time truncator (where the path tells the
agent "call `scan('<path>')` to recover the un-truncated value") and
the `search` tool (where the path tells the agent where each match
sits). The grammar must match exactly across producers — a single source
of truth lives here.

Convention:
- root            → `.`
- dict key, bare  → `.foo`
- dict key, other → `["foo-bar"]` (bracket-quoted, double-quote escaped)
- list index      → `[3]`
"""

from typing import Any, List


def render_path(path_stack: List[str]) -> str:
    """Render an accumulated path stack into a single jq expression.

    Empty stack renders as `.` (the root).
    """
    if not path_stack:
        return "."
    return "".join(path_stack)


def field_step(key: Any) -> str:
    """Render a dict-key step. Non-identifier keys get bracket-quoted."""
    text = str(key)
    if _is_bare_identifier(text):
        return "." + text
    return '["' + text.replace('"', '\\"') + '"]'


def index_step(index: int) -> str:
    """Render a list-index step."""
    return "[" + str(index) + "]"


def _is_bare_identifier(text: str) -> bool:
    if not text:
        return False
    if not (text[0].isalpha() or text[0] == "_"):
        return False
    return all(ch.isalnum() or ch == "_" for ch in text)
