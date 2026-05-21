import json
from typing import Any, Dict

from .. import context
from ..compression import span_tree_serializer
from . import executor


GET_TRACE_SPANS_SPEC: Dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "get_trace_spans",
        "description": (
            "Return a flat overview of the active trace and its spans: each "
            "span's id, name, type, parent_span_id, duration_ms, has_error, "
            "and truncated input/output. Call this first to orient yourself "
            "before drilling into specific spans."
        ),
        "parameters": {
            "type": "object",
            "properties": {},
            "additionalProperties": False,
        },
    },
}


class GetTraceSpansTool(executor.ToolExecutor):
    """Bird's-eye overview of the active trace's span tree.

    Takes no arguments — operates on the trace pre-seeded into the context.
    Acts as the mandatory first-tool-call landing (the loop forces
    tool_choice=required on the first model turn, so the judge always
    sees this overview before deciding whether to drill in via later tools).
    """

    name = "get_trace_spans"
    spec = GET_TRACE_SPANS_SPEC

    # noinspection PyMethodMayBeStatic
    # Instance method to satisfy the ToolExecutor protocol; stateless today,
    # but uniform with future tools that may carry per-instance config.
    def execute(self, arguments: str, ctx: context.TraceToolContext) -> str:
        overview = span_tree_serializer.serialize_overview(
            ctx.trace, ctx.spans, ctx.parent_by_child
        )
        return json.dumps(overview, default=str)
