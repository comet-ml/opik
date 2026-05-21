"""Tool-call loop driver for the agentic LLM judge.

Mirrors the backend `OnlineScoringLlmAsJudgeScorer` protocol:
- First call forces `tool_choice="required"` so the judge MUST call
  `get_trace_spans` (or any later tool) and see the trace before forming
  a verdict.
- Subsequent calls flip to `tool_choice="auto"`.
- A final, no-tool, structured-output turn produces the JSON verdict the
  caller parses.

Telemetry is emitted via `LOGGER` at the end of each loop run so an
operator running `evaluate()` over a dataset can spot misconfigured
judge models (the design-doc §9 "zero `read` calls on a large trace"
signal in particular).
"""

import collections
import dataclasses
import json
import logging
from typing import Any, Dict, List, Set, Tuple, Type

import pydantic

import opik
from opik.evaluation.models import base_model
from opik.decorator import context_manager

from . import context
from .compression import trace_compressor
from .tools import registry as tool_registry

LOGGER = logging.getLogger(__name__)

MAX_TOOL_CALL_ROUNDS = 10

# Trace size (in bytes of JSON-rendered composite) above which we expect
# the judge to issue at least one `read` call. Anchored to the trace
# compressor's FULL/MEDIUM boundary: at this size `read(type=trace)`
# would itself start truncating long strings, so a judge that skips
# `read` on a trace this large is making its verdict against the
# `get_trace_spans` skeleton alone — i.e., against information the
# `read` tool was specifically designed to recover. Single source of
# truth: retuning the compressor retunes this warning automatically.
# (`len // 4` token estimator → bytes = FULL_TOKEN_LIMIT * 4.)
LARGE_TRACE_BYTES = trace_compressor.FULL_TOKEN_LIMIT * 4


# Rewritten from an earlier version that included an "or return your
# verdict now" off-ramp. That branch conflicted with the system prompt's
# "MUST call the indicated tool when you see a truncation hint" rule —
# weaker judge models (gpt-4o-mini class) exploited the conflict by
# duplicating `get_trace_spans` to trigger this hint, then taking the
# verdict-now license to skip the drill-in. The rewrite removes that
# license and instead points at the most common forward path (`read` on
# a truncation hint), so the path of least resistance is "call a
# different tool", not "give up." See OPIK-6243 PR review transcript.
_DEDUP_HINT_TEMPLATE = (
    "Duplicate call: `{tool_name}` with the same arguments has already been "
    "executed in this loop and the result is unchanged. To make progress, "
    "call a different tool — typically `read(type=..., id=...)` to fetch "
    "content surfaced in a `[TRUNCATED ... — use read(...)]` hint, `scan` "
    "for a specific path, or `search` for a keyword. Repeating this call "
    "cannot produce new information."
)


@dataclasses.dataclass
class LoopTelemetry:
    """Per-loop-run counters surfaced to logs after the wrap-up turn."""

    rounds: int = 0
    tool_calls_by_name: Dict[str, int] = dataclasses.field(
        default_factory=lambda: collections.defaultdict(int)
    )
    # Counts how many tool calls were short-circuited because the model
    # repeated an identical (name, arguments) pair. Useful telemetry for
    # spotting judge models that loop on the overview without drilling in
    # — the failure mode the design doc §9 calls out for `gpt-5-nano`-class
    # models. Counted separately from `tool_calls_by_name` so the
    # registered-execution histogram stays clean.
    duplicate_calls: int = 0


@opik.track
def run_agentic_judge(
    *,
    model: base_model.OpikBaseModel,
    system_prompt: str,
    user_prompt: str,
    wrapup_instruction: str,
    registry: tool_registry.ToolRegistry,
    ctx: context.TraceToolContext,
    response_format: Type[pydantic.BaseModel],
    max_rounds: int = MAX_TOOL_CALL_ROUNDS,
) -> str:
    """Drive the tool-call loop and return the raw structured-output JSON.

    Returns the assistant's final text content (the JSON the response_format
    schema constrains). Caller parses it via the same path the one-shot
    LLMJudge uses.

    Raises whatever the underlying model raises on hard failures — the same
    contract as `LLMJudge._generate_and_parse`.
    """
    tool_specs: List[Any] = registry.specs()
    telemetry = LoopTelemetry()
    # Tracks every (tool_name, arguments) pair seen during this loop run.
    # Second and subsequent occurrences are short-circuited with a hint
    # (see `_DEDUP_HINT_TEMPLATE`) instead of re-executing the tool, which
    # forces forward progress when a judge model loops on the same call.
    # Arguments are compared as raw strings — models emit stable JSON for
    # the same intent, and the worst case of a missed dedup is one extra
    # tool execution, not incorrect behavior.
    seen_calls: Set[Tuple[str, str]] = set()

    messages: List[base_model.ConversationDict] = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": user_prompt},
    ]

    # First turn: force the judge to engage at least one tool. Models
    # vary in how aggressively they call tools when offered; requiring
    # one call on the first turn guarantees the overview lands in
    # context before the judge forms a verdict.
    response = model.generate_chat_completion(
        messages=messages,
        tools=tool_specs,
        tool_choice="required",
        temperature=0,
    )
    messages.append(_assistant_message_from_response(response))

    for round_index in range(max_rounds):
        tool_calls = response.get("tool_calls") or []
        if not tool_calls:
            break
        telemetry.rounds += 1

        for call in tool_calls:
            tool_name = call["function"]["name"]
            arguments = call["function"].get("arguments", "{}")
            telemetry.tool_calls_by_name[tool_name] += 1

            call_key = (tool_name, arguments)
            with context_manager.start_as_current_span(
                name=tool_name,
                type="tool",
                input={"input": arguments},
                metadata={"call_key": call_key, "round_index": round_index},
            ) as span:
                if call_key in seen_calls:
                    # Same call already executed this loop — short-circuit
                    # with a hint so the model stops re-querying and either
                    # drills in with a different tool/args or finalizes.
                    # Counted separately so the by-name histogram stays an
                    # honest "tool-executed-N-times" record.
                    telemetry.duplicate_calls += 1
                    result = _DEDUP_HINT_TEMPLATE.format(tool_name=tool_name)
                else:
                    seen_calls.add(call_key)
                    result = registry.execute(tool_name, arguments, ctx)
                    messages.append(
                        {
                            "role": "tool",
                            "tool_call_id": call["id"],
                            "content": result,
                        }
                    )
                span.output = {"output": result}

        # Follow-up turns let the model decide whether more tools are
        # needed. Stop the loop as soon as the model produces no further
        # tool calls.
        response = model.generate_chat_completion(
            messages=messages,
            tools=tool_specs,
            tool_choice="auto",
            temperature=0,
        )
        messages.append(_assistant_message_from_response(response))
    else:
        LOGGER.warning(
            "Agentic judge reached MAX_TOOL_CALL_ROUNDS=%d without terminating; "
            "forcing wrap-up.",
            max_rounds,
        )

    _emit_telemetry(telemetry, ctx)

    # Wrap-up turn: drop tools entirely and require structured JSON
    # matching the assertion schema. Putting the wrap-up instruction in
    # a fresh user message helps providers that don't separate
    # tool_choice from response_format in the same call.
    messages.append({"role": "user", "content": wrapup_instruction})
    verdict = model.generate_chat_completion(
        messages=messages,
        response_format=response_format,
        temperature=0,
    )
    return verdict.get("content") or ""


def _emit_telemetry(telemetry: LoopTelemetry, ctx: context.TraceToolContext) -> None:
    """Surface per-run counters and the design-doc §9 "low engagement"
    warning.

    Two log levels:
    - INFO: rounds + tool-call breakdown. Cheap to enable, lets an
      operator see what each judge invocation cost.
    - WARNING: zero `read` calls on a trace whose serialized composite
      exceeds `LARGE_TRACE_BYTES`. Signals the configured model isn't
      engaging with the tools (the `gpt-5-nano`-style failure mode the
      backend doc calls out).
    """
    tool_breakdown = ", ".join(
        f"{name}={count}"
        for name, count in sorted(telemetry.tool_calls_by_name.items())
    )
    LOGGER.info(
        "Agentic loop finished: rounds=%d, tool_calls=[%s], duplicates=%d",
        telemetry.rounds,
        tool_breakdown,
        telemetry.duplicate_calls,
    )

    read_engaged = True
    if telemetry.tool_calls_by_name.get("read", 0) == 0:
        if _trace_size_bytes(ctx) > LARGE_TRACE_BYTES:
            read_engaged = False
            LOGGER.warning(
                "Agentic judge produced a verdict on a large trace (>%d bytes) "
                "without ever calling `read`. The configured judge model may "
                "not be engaging the tools — consider switching models or "
                "checking provider tool-call support.",
                LARGE_TRACE_BYTES,
            )

    opik.opik_context.update_current_span(
        metadata={
            "agentic_loop_rounds": telemetry.rounds,
            "agentic_loop_tool_calls": telemetry.tool_calls_by_name,
            "agentic_loop_duplicate_calls": telemetry.duplicate_calls,
            "agentic_loop_trace_size_bytes": _trace_size_bytes(ctx),
            "agentic_loop_read_engaged": read_engaged,
        }
    )


def _trace_size_bytes(ctx: context.TraceToolContext) -> int:
    """Approximate the trace's serialized size for the engagement
    heuristic. JSON-rendered composite is the right proxy — same shape
    the agent would see via `read(type=trace)`.
    """
    from .entity_ref import EntityRef, EntityType

    cached = ctx.get_cached(EntityRef(EntityType.TRACE, ctx.trace.id))
    if cached is None:
        return 0
    try:
        return len(json.dumps(cached, default=str))
    except (TypeError, ValueError):
        return 0


def _assistant_message_from_response(
    response: base_model.ConversationDict,
) -> base_model.ConversationDict:
    """Build the assistant message to re-feed into the next turn.

    Strip provider-specific extras; keep role/content/tool_calls only.
    """
    assistant: base_model.ConversationDict = {"role": "assistant"}
    content = response.get("content")
    if content is not None:
        assistant["content"] = content
    tool_calls = response.get("tool_calls")
    if tool_calls:
        assistant["tool_calls"] = tool_calls
    return assistant
