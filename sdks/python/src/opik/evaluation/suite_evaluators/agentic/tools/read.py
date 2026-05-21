"""`read` tool — fetch a trace or span at the requested compression tier.

Resolves the entity via the per-evaluation cache first, falling back to
the emulator for refs not pre-seeded into the context. Dispatches to
the per-entity compressor and returns a JSON response envelope:

    {
      "type": "trace|span",
      "id": "<id>",
      "tier": "FULL|MEDIUM|SKELETON",
      "data": <compressed payload>
    }

Errors return `{"error": "..."}` JSON. Tools never raise out of the
loop — bad arguments and missing entities are surfaced to the judge so
it can recover by trying a different ref or tier.
"""

import json
import logging
from typing import Any, Dict, Optional

from .. import context, entity_ref
from ..compression import (
    generic_compressor,
    tier as tier_module,
    trace_compressor,
)
from . import executor, tool_args

LOGGER = logging.getLogger(__name__)


READ_SPEC: Dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "read",
        "description": (
            "Fetch a trace or span by id at the requested compression tier. "
            "Returns the cached entity payload wrapped in a response "
            "envelope; large entities are compressed (MEDIUM truncates "
            "strings with `scan`-path hints; SKELETON drops content and "
            "keeps structure). Use `scan(...)` to retrieve untruncated "
            "values for paths surfaced in truncation hints."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "type": {
                    "type": "string",
                    "enum": [t.value for t in entity_ref.EntityType],
                    "description": "Entity type to fetch.",
                },
                "id": {
                    "type": "string",
                    "description": "Entity id.",
                },
                "tier": {
                    "type": "string",
                    "enum": [t.value for t in tier_module.CompressionTier],
                    "description": (
                        "Optional. Force a specific tier; when omitted the "
                        "tier is picked from the entity's estimated size."
                    ),
                },
            },
            "required": ["type", "id"],
            "additionalProperties": False,
        },
    },
}


class ReadTool(executor.ToolExecutor):
    """Cache-backed entity reader with adaptive compression."""

    name = "read"
    spec = READ_SPEC

    # noinspection PyMethodMayBeStatic
    # Instance method to satisfy the ToolExecutor protocol; stateless today,
    # but uniform with future tools that may carry per-instance config.
    def execute(self, arguments: str, ctx: context.TraceToolContext) -> str:
        parsed = _parse_arguments(arguments)
        if "error" in parsed:
            return json.dumps(parsed)

        ref = parsed["ref"]
        forced_tier: Optional[tier_module.CompressionTier] = parsed["tier"]

        cached = ctx.get_cached(ref)
        if cached is None:
            cached = ctx.lookup_from_emulator(ref)
            if cached is None:
                return json.dumps(
                    {
                        "error": (
                            f"Entity (type={ref.type.value}, id={ref.id}) "
                            "not found in local trace cache"
                        )
                    }
                )
            ctx.cache(ref, cached)

        if ref.type is entity_ref.EntityType.TRACE:
            result = _compress_trace(cached, forced_tier)
        else:
            result = generic_compressor.compress(cached, forced_tier)

        return json.dumps(
            {
                "type": ref.type.value,
                "id": ref.id,
                "tier": result.tier.value,
                "data": result.payload,
            },
            default=str,
        )


def _compress_trace(
    cached: Dict[str, Any], forced_tier: Optional[tier_module.CompressionTier]
) -> tier_module.CompressionResult:
    """Run `TraceCompressor` against a cached `{trace, spans}` composite.

    `parent_by_child` for the SKELETON tier is reconstructed from the
    cached spans' `parent_span_id` field; we deliberately don't reach
    back into the emulator here, so this stays a pure transform of the
    cache contents (matching how `scan` will operate in Phase 3).
    """
    trace = cached.get("trace") or {}
    spans = cached.get("spans") or []
    parent_by_child: Dict[str, Optional[str]] = {
        span["id"]: span.get("parent_span_id") for span in spans if "id" in span
    }
    return trace_compressor.compress(
        full_json=cached,
        trace=trace,
        spans=spans,
        parent_by_child=parent_by_child,
        forced_tier=forced_tier,
    )


def _parse_arguments(arguments: str) -> Dict[str, Any]:
    envelope = tool_args.parse_envelope(arguments)
    if envelope.error is not None:
        return {"error": envelope.error}
    raw, ref = envelope.unwrap()

    # `tier` is the only field unique to `read`; keep its parsing here so
    # `tool_args` stays focused on the shape every tool shares.
    forced_tier_raw = raw.get("tier")
    forced_tier: Optional[tier_module.CompressionTier] = None
    if forced_tier_raw is not None:
        try:
            forced_tier = tier_module.CompressionTier(forced_tier_raw)
        except ValueError:
            return {
                "error": (
                    f"Unsupported tier '{forced_tier_raw}'. "
                    f"Supported: {[t.value for t in tier_module.CompressionTier]}"
                )
            }

    return {"ref": ref, "tier": forced_tier}
