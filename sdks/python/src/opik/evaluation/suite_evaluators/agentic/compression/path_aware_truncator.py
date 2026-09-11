"""Recursive truncator that tracks the jq-style path of each string.

Used by the MEDIUM tier of every adaptive compressor. Walks a JSON-shaped
payload (dict / list / scalar) and replaces oversized strings with a
truncated head + `scan`-path hint suffix (see `string_truncator`).

Path syntax produced here matches what the `scan` tool consumes (design
doc §5.3): dotted field access for dict keys, bracketed integers for
list indices. Root is `.`; nested fields are `.foo.bar`; arrays are
`.foo[2]`; mixed is `.spans[0].input.messages[3].content`.

Mirrors `PathAwareTruncator.java` but uses a simple recursive function
with a path-stack list instead of the Java visitor pattern.
"""

from typing import Any, List

from .. import path_format
from . import string_truncator


def truncate_strings(payload: Any, max_string_chars: int) -> Any:
    """Return a copy of `payload` with strings longer than
    `max_string_chars` replaced by truncated head + `scan`-path suffix.

    Non-string values are passed through unchanged. Containers (dict /
    list) are walked recursively; tuples are coerced to lists since the
    output is destined for JSON serialization. Cycles are not expected
    in the JSON-shaped trace data we operate on; if one is ever
     introduced, the recursion will hit Python's default limit and raise.
    """
    return _walk(payload, max_string_chars, [])


def _walk(node: Any, max_chars: int, path_stack: List[str]) -> Any:
    if isinstance(node, str):
        return string_truncator.truncate(
            node, max_chars, path_format.render_path(path_stack)
        )
    if isinstance(node, dict):
        return {
            key: _descend(
                value, max_chars, path_stack, step=path_format.field_step(key)
            )
            for key, value in node.items()
        }
    if isinstance(node, (list, tuple)):
        return [
            _descend(item, max_chars, path_stack, step=path_format.index_step(idx))
            for idx, item in enumerate(node)
        ]
    return node


def _descend(value: Any, max_chars: int, path_stack: List[str], step: str) -> Any:
    path_stack.append(step)
    try:
        return _walk(value, max_chars, path_stack)
    finally:
        path_stack.pop()
