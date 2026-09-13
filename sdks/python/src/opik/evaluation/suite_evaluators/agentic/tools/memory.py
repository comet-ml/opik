"""`memory` tool — persistent Short-Term Memory (STM) for cross-turn reasoning.

The agentic judge drives a tool-call loop with `read` / `scan` / `search`
to drill into a trace, but by default it must carry every observation in
its own context window across rounds. On long traces that exceed the
model's native context budget this causes attention dilution (and, past
the limit, total failure): the evidence the judge already gathered gets
re-read or dropped as the conversation grows.

This tool ports the discrete novel component of SAFARI (Scaling long-
horizon Agentic Fault AttRibution via active Investigation) — a
persistent Short-Term Memory the judge *writes distilled findings to* and
*reads back* on later turns, decoupling diagnostic accuracy from the
model's context limit. The judge offloads what it has already concluded
("span s-3 errored with X", "tool_call to `search_api` absent") and
recalls it in O(note) instead of re-scanning the trace.

Adapted from: SAFARI, arXiv:2606.24626v1. The full SAFARI method also
adds active investigation policy and trajectory-segment retrieval; only
the STM primitive is ported here, as a `ToolExecutor` that slots into
the existing tool-call loop with no changes to its contract.

State lives on the tool instance, so it persists across every round of a
single `run_agentic_judge` loop (the registry built by
`default_tool_registry` is constructed fresh per `AgenticLLMJudge`, i.e.
per evaluation item). Memory never escapes the loop and is not shared
across traces.
"""

import json
from typing import Any, Dict, List, Optional

from .. import context
from . import executor, tool_args

# Recall responses are capped to the same 16 KB budget the other drill-in
# tools use, so a misbehaving judge that records an oversized note cannot
# re-introduce the context bloat this tool exists to avoid. Notes are
# judge-authored distilled findings and are expected to be small; the cap
# is a defensive backstop, not the common path.
_MAX_RECALL_BYTES = 16 * 1024

_ACTIONS = ("record", "recall", "clear")


MEMORY_SPEC: Dict[str, Any] = {
    "type": "function",
    "function": {
        "name": "memory",
        "description": (
            "Persistent short-term memory across turns of this evaluation. "
            "Use `record` to offload a distilled finding you have already "
            "established (a conclusion, an id of interest, a ruled-out "
            "hypothesis) so you do not have to re-scan the trace or hold it "
            "in context on later turns; use `recall` to read your notes "
            "back; use `clear` to reset. Notes persist for the whole "
            "evaluation and never leave it."
        ),
        "parameters": {
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": list(_ACTIONS),
                    "description": "What to do with the notepad.",
                },
                "key": {
                    "type": "string",
                    "description": (
                        "`record`: handle to store the note under (overwrites "
                        "an existing note with the same key). `recall`: "
                        "optional substring filter on the key (case-"
                        "sensitive); omit to read every note."
                    ),
                },
                "note": {
                    "type": "string",
                    "description": "Required for `record`: the finding text.",
                },
            },
            "required": ["action"],
            "additionalProperties": False,
        },
    },
}


class ShortTermMemoryTool(executor.ToolExecutor):
    """Persistent notepad of distilled findings for the agentic judge.

    Cross-turn reasoning memory: the judge writes conclusions to a keyed
    notepad and reads them back later, instead of carrying raw evidence in
    context. Implements SAFARI's Short-Term Memory primitive as a stateful
    `ToolExecutor`.
    """

    name = "memory"
    spec = MEMORY_SPEC

    def __init__(self) -> None:
        # Insertion-ordered so `recall` lists notes in the order the judge
        # recorded them, which is the natural narrative of an investigation.
        self._notes: Dict[str, str] = {}

    def execute(self, arguments: str, ctx: context.TraceToolContext) -> str:
        parsed = _parse_arguments(arguments)
        if "error" in parsed:
            return json.dumps(parsed)

        action = parsed["action"]
        if action == "record":
            return self._record(parsed["key"], parsed["note"])
        if action == "recall":
            return self._recall(parsed.get("key"))
        # action == "clear" — the only remaining validated value.
        return self._clear()

    def _record(self, key: str, note: str) -> str:
        self._notes[key] = note
        return json.dumps({"status": "recorded", "key": key, "count": len(self._notes)})

    def _recall(self, key_filter: Optional[str]) -> str:
        selected: List[Dict[str, str]] = [
            {"key": k, "note": v}
            for k, v in self._notes.items()
            if key_filter is None or key_filter in k
        ]
        body = json.dumps({"notes": selected, "count": len(selected)})
        if len(body.encode("utf-8")) <= _MAX_RECALL_BYTES:
            return body
        # Defensive cap: drop notes from the end until it fits, then flag the
        # truncation so the judge can narrow its `key` filter.
        while selected and len(body.encode("utf-8")) > _MAX_RECALL_BYTES:
            selected.pop()
            body = json.dumps(
                {"notes": selected, "count": len(selected), "truncated": True}
            )
        return body

    def _clear(self) -> str:
        removed = len(self._notes)
        self._notes.clear()
        return json.dumps({"status": "cleared", "count": removed})


def _parse_arguments(arguments: str) -> Dict[str, Any]:
    raw_result = tool_args.parse_arguments_object(arguments)
    if raw_result.error is not None:
        return {"error": raw_result.error}
    raw = raw_result.unwrap()

    action = raw.get("action")
    if not isinstance(action, str) or not action:
        return {"error": "Missing required 'action'"}
    if action not in _ACTIONS:
        return {
            "error": (f"Unsupported action '{action}'. Supported: {list(_ACTIONS)}")
        }

    result: Dict[str, Any] = {"action": action}

    if action == "record":
        key_result = tool_args.require_string(raw, "key")
        if key_result.error is not None:
            return {"error": key_result.error}
        note_result = tool_args.require_string(raw, "note")
        if note_result.error is not None:
            return {"error": note_result.error}
        result["key"] = key_result.unwrap()
        result["note"] = note_result.unwrap()
        return result

    # recall / clear: `key` is optional. Forward it only when present and a
    # non-empty string so `_recall` can tell "filter by X" from "read all".
    key = raw.get("key")
    if isinstance(key, str) and key:
        result["key"] = key
    return result
