"""`search` tool — case-insensitive regex over string values in a cached entity.

Locates substrings the agent might otherwise miss in a large trace
(error messages, tool-call markers, model names buried deep in
metadata). Returns one match per line with the jq path to each hit so
the agent can drill in via `scan`.

Output envelope mirrors `scan` so the prompt teaches one rendering
convention:

    [search: <type>:<id> | pattern='<p>' (| path='<p>')?]
    <jq-path>: <truncated-value-with-match>
    <jq-path>: <truncated-value-with-match>
    ...

Errors use the same header with `| ERROR`:

    [search: <type>:<id> | pattern='<p>' | ERROR]
    <reason>

Caps:
- 50 matches (further matches dropped; suffix tells the agent to narrow)
- 200 chars per value (head-truncated with a `[+N chars]` tail)
- 16 KB total output (final tail-truncation via the same suffix as scan)
"""

import logging
import re
from typing import Any, Dict, Iterator, List, Optional, Tuple

from .. import context, entity_ref, path_format
from . import executor, tool_args, path_evaluator

LOGGER = logging.getLogger(__name__)

MAX_MATCHES = 50
VALUE_TRUNCATION_LENGTH = 200
OUTPUT_BYTE_CAP = 16 * 1024
TRUNCATION_SUFFIX = "\n[TRUNCATED — narrow the pattern or `path` to reduce results]"
MATCH_LIMIT_SUFFIX = (
    "\n[+ more matches dropped — narrow the pattern or `path` to see them]"
)


SEARCH_SPEC: Dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "search",
        "description": (
            "Case-insensitive regex search across every string value in a "
            "cached trace or span. Returns up to 50 matches, each as "
            "'<jq-path>: <value>'. Use against the active trace when an "
            "assertion mentions a keyword (an error string, a tool name, "
            "a model id) but you don't yet know which span holds it — "
            "then `scan` the surfaced path for the full value."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "type": {
                    "type": "string",
                    "enum": [t.value for t in entity_ref.EntityType],
                    "description": "Entity type the search runs against.",
                },
                "id": {
                    "type": "string",
                    "description": "Entity id.",
                },
                "pattern": {
                    "type": "string",
                    "description": (
                        "Python regular expression. Matched case-insensitively "
                        "against every string value reachable from the entity "
                        "(or, when `path` is supplied, from the path's result)."
                    ),
                },
                "path": {
                    "type": "string",
                    "description": (
                        "Optional. A `scan`-shaped expression used to narrow "
                        "the search scope. The search walks only values "
                        "reachable from the expression's result(s)."
                    ),
                },
            },
            "required": ["type", "id", "pattern"],
            "additionalProperties": False,
        },
    },
}


class SearchTool(executor.ToolExecutor):
    """Regex search over a cached trace or span with jq-path output."""

    name = "search"
    spec = SEARCH_SPEC

    # noinspection PyMethodMayBeStatic
    # Instance method to satisfy the ToolExecutor protocol; stateless today,
    # but uniform with future tools that may carry per-instance config.
    def execute(self, arguments: str, ctx: context.TraceToolContext) -> str:
        parsed = _parse_arguments(arguments)
        if "error" in parsed:
            return _error_envelope(
                parsed.get("type", "?"),
                parsed.get("id", "?"),
                parsed.get("pattern", "?"),
                parsed.get("path"),
                parsed["error"],
            )

        ref: entity_ref.EntityRef = parsed["ref"]
        pattern_str: str = parsed["pattern"]
        # `path` is normalized through the shared helper so a missing
        # leading `.` (e.g. `dataset_item` instead of `.dataset_item`)
        # doesn't bounce the call back to the model. The regex
        # `pattern` is deliberately NOT touched — it's not a path.
        path_expr: Optional[str] = (
            path_evaluator.normalize_expression(parsed["path"])
            if parsed["path"]
            else None
        )

        try:
            pattern = re.compile(pattern_str, re.IGNORECASE)
        except re.error as exc:
            return _error_envelope(
                ref.type.value,
                ref.id,
                pattern_str,
                path_expr,
                f"Invalid regex: {exc}",
            )

        cached = ctx.get_cached(ref)
        if cached is None:
            cached = ctx.lookup_from_emulator(ref)
            if cached is None:
                return _error_envelope(
                    ref.type.value,
                    ref.id,
                    pattern_str,
                    path_expr,
                    (
                        f"Entity (type={ref.type.value}, id={ref.id}) "
                        "not found in local trace cache"
                    ),
                )
            ctx.cache(ref, cached)

        # When `path` is supplied, narrow the search to its result(s).
        # Each result becomes a separate search root; the jq path for
        # matches is anchored at the result's location within the entity
        # so the agent can paste it back into `scan`.
        roots = _resolve_roots(cached, path_expr)
        if isinstance(roots, str):
            return _error_envelope(
                ref.type.value, ref.id, pattern_str, path_expr, roots
            )

        body = _render_matches(roots, pattern)
        return _ok_envelope(ref.type.value, ref.id, pattern_str, path_expr, body)


# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------


def _parse_arguments(arguments: str) -> Dict[str, Any]:
    envelope = tool_args.parse_envelope(arguments)
    if envelope.error is not None:
        return {"error": envelope.error}
    raw, ref = envelope.unwrap()

    pattern_result = tool_args.require_string(raw, "pattern")
    if pattern_result.error is not None:
        return {"error": pattern_result.error}

    # `path` is optional but must be a string when present — not a
    # generic require_string concern, so kept inline.
    path_raw = raw.get("path")
    if path_raw is not None and not isinstance(path_raw, str):
        return {"error": "Optional 'path' must be a string"}

    return {
        "ref": ref,
        "pattern": pattern_result.unwrap(),
        "path": path_raw if path_raw else None,
        "type": ref.type.value,
        "id": ref.id,
    }


# ---------------------------------------------------------------------------
# Search root resolution
# ---------------------------------------------------------------------------


def _resolve_roots(cached: Dict[str, Any], path_expr: Optional[str]) -> Any:
    """Return a list of `(anchor_steps, value)` pairs to search from.

    Without `path`, the single root is the entity itself anchored at `.`.
    With `path`, each result of the expression becomes its own root. The
    anchor records where in the entity the result lives so match paths
    stay relative to the cached entity (not to the path-expression
    result), which is what the agent needs to paste into `scan`.

    Returns the string reason on error so the caller can wrap it in an
    error envelope without raising.
    """
    if not path_expr:
        return [([], cached)]
    try:
        # Re-parse not strictly necessary here, but we want consistent
        # error messages between scan and search.
        anchored = list(_evaluate_with_anchor(path_expr, cached))
    except path_evaluator.PathError as exc:
        return f"Unsupported path expression: {exc}. See prompt examples."
    except path_evaluator.PathLimitError as exc:
        return str(exc)
    return anchored


def _evaluate_with_anchor(
    expression: str, root: Any
) -> Iterator[Tuple[List[str], Any]]:
    """Evaluate `expression` against `root` and yield each result alongside
    a path stack that records its location relative to `root`.

    Implemented by replaying the parsed step list with our own walker so
    we can track each result's path — `path_evaluator.evaluate` only
    yields values. Limited to the same grammar (§5.3).
    """
    ast = path_evaluator.parse(expression)
    yield from _walk_with_anchor(ast.steps, root, [])


def _walk_with_anchor(
    steps: List[Any], value: Any, anchor: List[str]
) -> Iterator[Tuple[List[str], Any]]:
    if not steps:
        yield list(anchor), value
        return
    step, rest = steps[0], steps[1:]
    if isinstance(step, path_evaluator.Root):
        yield from _walk_with_anchor(rest, value, anchor)
        return
    if isinstance(step, path_evaluator.Field):
        if isinstance(value, dict) and step.name in value:
            anchor.append(path_format.field_step(step.name))
            try:
                yield from _walk_with_anchor(rest, value[step.name], anchor)
            finally:
                anchor.pop()
        return
    if isinstance(step, path_evaluator.Index):
        if isinstance(value, list):
            idx = step.idx
            if -len(value) <= idx < len(value):
                resolved_idx = idx if idx >= 0 else len(value) + idx
                anchor.append(path_format.index_step(resolved_idx))
                try:
                    yield from _walk_with_anchor(rest, value[idx], anchor)
                finally:
                    anchor.pop()
        return
    if isinstance(step, path_evaluator.Iterate):
        if isinstance(value, list):
            for i, item in enumerate(value):
                anchor.append(path_format.index_step(i))
                try:
                    yield from _walk_with_anchor(rest, item, anchor)
                finally:
                    anchor.pop()
        return
    # Slice and RecursiveDescent are intentionally not supported as
    # search-narrowing paths — slices produce a list root which the
    # walker would dive into anyway, and `..` makes the anchor ambiguous.
    # Tell the agent so it can adapt.
    raise path_evaluator.PathError(
        "search `path` argument supports field access, index, and `[]` "
        "iteration only — use `scan` for slices and `..` traversals"
    )


# ---------------------------------------------------------------------------
# Match walking
# ---------------------------------------------------------------------------


def _walk_strings(value: Any, anchor: List[str]) -> Iterator[Tuple[str, str]]:
    """Yield `(rendered_path, string_value)` for every string under `value`."""
    if isinstance(value, str):
        yield path_format.render_path(anchor), value
        return
    if isinstance(value, dict):
        for key, child in value.items():
            anchor.append(path_format.field_step(key))
            try:
                yield from _walk_strings(child, anchor)
            finally:
                anchor.pop()
    elif isinstance(value, (list, tuple)):
        for i, child in enumerate(value):
            anchor.append(path_format.index_step(i))
            try:
                yield from _walk_strings(child, anchor)
            finally:
                anchor.pop()


def _render_matches(
    roots: List[Tuple[List[str], Any]], pattern: "re.Pattern[str]"
) -> str:
    lines: List[str] = []
    match_limit_hit = False
    for anchor_steps, root_value in roots:
        # Each anchor seeds the walker so emitted paths stay rooted at
        # the cached entity, not the search-narrowing path's result.
        anchor = list(anchor_steps)
        for rendered_path, value in _walk_strings(root_value, anchor):
            if not pattern.search(value):
                continue
            if len(lines) >= MAX_MATCHES:
                match_limit_hit = True
                break
            lines.append(f"{rendered_path}: {_truncate_value(value)}")
        if match_limit_hit:
            break

    if not lines:
        return "<no matches>"

    body = "\n".join(lines)
    if len(body.encode("utf-8")) > OUTPUT_BYTE_CAP:
        # Re-emit lines stopping just before the cap is breached, then
        # tag with the refine-suffix. The previous accumulation is the
        # cheapest path to a tight cap.
        body = _trim_to_cap(lines)
        body += TRUNCATION_SUFFIX
    elif match_limit_hit:
        body += MATCH_LIMIT_SUFFIX
    return body


def _truncate_value(value: str) -> str:
    if len(value) <= VALUE_TRUNCATION_LENGTH:
        return value
    dropped = len(value) - VALUE_TRUNCATION_LENGTH
    return value[:VALUE_TRUNCATION_LENGTH] + f" [+{dropped:,} chars]"


def _trim_to_cap(lines: List[str]) -> str:
    kept: List[str] = []
    accumulated = 0
    for line in lines:
        delta = len(line.encode("utf-8")) + (1 if kept else 0)
        if accumulated + delta > OUTPUT_BYTE_CAP:
            break
        kept.append(line)
        accumulated += delta
    return "\n".join(kept)


# ---------------------------------------------------------------------------
# Envelopes
# ---------------------------------------------------------------------------


def _ok_envelope(
    type_: str, id_: str, pattern: str, path: Optional[str], body: str
) -> str:
    header = _header(type_, id_, pattern, path)
    return f"{header}\n{body}"


def _error_envelope(
    type_: str,
    id_: Optional[str],
    pattern: str,
    path: Optional[str],
    reason: str,
) -> str:
    header = _header(type_, id_, pattern, path, error=True)
    return f"{header}\n{reason}"


def _header(
    type_: str,
    id_: Optional[str],
    pattern: str,
    path: Optional[str],
    *,
    error: bool = False,
) -> str:
    parts = [f"[search: {type_}:{id_} | pattern='{pattern}'"]
    if path:
        parts.append(f"| path='{path}'")
    if error:
        parts.append("| ERROR")
    return " ".join(parts) + "]"
