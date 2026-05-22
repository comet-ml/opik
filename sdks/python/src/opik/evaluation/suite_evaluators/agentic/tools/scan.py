"""`scan` tool — pure-Python path/predicate query over a cached entity.

Replaces the backend's `jq` tool for the SDK. Arguments mirror backend
shape exactly (`type`, `id`, `expression`) so the prompt can teach the
same fields. Supported grammar is the constrained subset documented in
design doc §5.3; anything outside that grammar comes back as a
structured error so the model can retry within the prompt examples.

Output format mirrors the backend `jq` tool but swaps the header tag:

    [scan: <type>:<id> | expression='<expr>']
    <one result per line>

Errors use the same header with the `ERROR` suffix:

    [scan: <type>:<id> | expression='<expr>' | ERROR]
    <reason>

A 16 KB output cap is applied to the rendered result lines. If the
total exceeds the cap the head is kept and a truncation suffix tells
the model to refine the expression.
"""

import json
import logging
from typing import Any, Dict, List, Optional

from .. import context, entity_ref
from . import executor, tool_args, path_evaluator

LOGGER = logging.getLogger(__name__)

OUTPUT_BYTE_CAP = 16 * 1024
TRUNCATION_SUFFIX = "\n[TRUNCATED — refine the expression to narrow the result set]"


SCAN_SPEC: Dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "scan",
        "description": (
            "Query a cached trace or span via a small jq-shaped path "
            "expression. Use after `read` (or directly against the "
            "active trace whose overview is shown in the opening "
            "message) when you need a specific value, field, or "
            "filtered subset out of an entity's JSON. "
            "Supports dotted field access, integer index, slice, `[]` "
            "iteration, `..` recursive descent, `..|strings`, and "
            "`..|select(<predicate>)`. Predicates accept `==`, `!=`, "
            "`?` (key present), `and`, `or`, `not`, and parentheses."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "type": {
                    "type": "string",
                    "enum": [t.value for t in entity_ref.EntityType],
                    "description": "Entity type the expression queries.",
                },
                "id": {
                    "type": "string",
                    "description": "Entity id.",
                },
                "expression": {
                    "type": "string",
                    "description": (
                        "Path expression. Examples: `.`, `.input`, "
                        "`.spans[0].name`, `.spans[]`, `..|strings`, "
                        "`..|select(.error_info?)`, "
                        '`..|select(.name == "tool_call")`.'
                    ),
                },
            },
            "required": ["type", "id", "expression"],
            "additionalProperties": False,
        },
    },
}


class ScanTool(executor.ToolExecutor):
    """Path/predicate query over cached entities."""

    name = "scan"
    spec = SCAN_SPEC

    # noinspection PyMethodMayBeStatic
    # Instance method to satisfy the ToolExecutor protocol; stateless today,
    # but uniform with future tools that may carry per-instance config.
    def execute(self, arguments: str, ctx: context.TraceToolContext) -> str:
        parsed = _parse_arguments(arguments)
        if "error" in parsed:
            return _error_envelope(
                parsed.get("type", "?"),
                parsed.get("id", "?"),
                parsed.get("expression", "?"),
                parsed["error"],
            )

        ref: entity_ref.EntityRef = parsed["ref"]
        expression: str = parsed["expression"]

        cached = ctx.get_cached(ref)
        if cached is None:
            cached = ctx.lookup_from_emulator(ref)
            if cached is None:
                return _error_envelope(
                    ref.type.value,
                    ref.id,
                    expression,
                    (
                        f"Entity (type={ref.type.value}, id={ref.id}) "
                        "not found in local trace cache"
                    ),
                )
            ctx.cache(ref, cached)

        try:
            results = path_evaluator.evaluate(expression, cached)
        except path_evaluator.PathError as exc:
            return _error_envelope(
                ref.type.value,
                ref.id,
                expression,
                f"Unsupported expression: {exc}. See prompt examples.",
            )
        except path_evaluator.PathLimitError as exc:
            return _error_envelope(ref.type.value, ref.id, expression, str(exc))

        body = _render_results(results)
        return _ok_envelope(ref.type.value, ref.id, expression, body)


def _parse_arguments(arguments: str) -> Dict[str, Any]:
    envelope = tool_args.parse_envelope(arguments)
    if envelope.error is not None:
        return {"error": envelope.error}
    raw, ref = envelope.unwrap()

    expression_result = tool_args.require_string(raw, "expression")
    if expression_result.error is not None:
        return {"error": expression_result.error}

    return {
        "ref": ref,
        "expression": expression_result.unwrap(),
        "type": ref.type.value,
        "id": ref.id,
    }


def _render_results(results: List[Any]) -> str:
    """Render results one value per line, capped at `OUTPUT_BYTE_CAP`.

    Each value is JSON-rendered with `default=str` so datetime-like
    values don't blow up. Strings emit as bare text (no enclosing
    quotes) — same as the backend `jq` rendering.
    """
    if not results:
        return "<no matches>"
    lines: List[str] = []
    accumulated = 0
    for value in results:
        rendered = _render_value(value)
        # +1 for the newline separator (except the first line).
        delta = len(rendered) + (1 if lines else 0)
        if accumulated + delta > OUTPUT_BYTE_CAP:
            head = "\n".join(lines)
            return head + TRUNCATION_SUFFIX
        lines.append(rendered)
        accumulated += delta
    return "\n".join(lines)


def _render_value(value: Any) -> str:
    if isinstance(value, str):
        return value
    try:
        return json.dumps(value, default=str)
    except (TypeError, ValueError):
        return str(value)


def _ok_envelope(type_: str, id_: str, expression: str, body: str) -> str:
    header = f"[scan: {type_}:{id_} | expression='{expression}']"
    return f"{header}\n{body}"


def _error_envelope(
    type_: str, id_: Optional[str], expression: str, reason: str
) -> str:
    header = f"[scan: {type_}:{id_} | expression='{expression}' | ERROR]"
    return f"{header}\n{reason}"
