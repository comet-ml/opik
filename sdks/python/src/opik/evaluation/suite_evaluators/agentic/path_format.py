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

import json
from typing import Any, List


RESERVED_KEYWORDS = frozenset(
    {"and", "or", "not", "select", "strings", "true", "false", "null"}
)
"""Identifiers the path parser lexes as keywords rather than as a dotted field.

They still have to be reachable as dict keys, so `field_step` bracket-quotes
them. The parser reads this set from here — see `tools/path_evaluator.py` — so
that the producer and the consumer cannot disagree about which keys the dotted
form can express.
"""


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
    return "[" + json.dumps(text, ensure_ascii=False) + "]"


def index_step(index: int) -> str:
    """Render a list-index step."""
    return "[" + str(index) + "]"


def _is_bare_identifier(text: str) -> bool:
    # A bare key is rendered as `.foo`, so it must be something the dotted form
    # can actually express. A reserved word would lex as a keyword on the way
    # back in, so `.select` could not be pasted into `scan` — the grammar in
    # `tools/path_evaluator.py` reserves exactly these.
    if text in RESERVED_KEYWORDS:
        return False
    if not text or not text.isascii():
        return False
    if not (text[0].isalpha() or text[0] == "_"):
        return False
    return all(ch.isalnum() or ch == "_" for ch in text)
