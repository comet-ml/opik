"""Tool-call loop driver for the agentic LLM judge.

The opening user message carries a pre-rendered flat trace overview, so
the judge can decide on the first turn whether any drill-in tool
(`read` / `scan` / `search`) is needed. `tool_choice` stays on `"auto"`
throughout, and `response_format` is set on every turn — so any turn
where the model declines to call a tool already produces the structured
JSON verdict the caller parses. A dedicated wrap-up turn only runs as a
force-finalize when the loop exits via `max_rounds`.

Telemetry is emitted via `LOGGER` at the end of each loop run so an
operator running `evaluate()` over a dataset can spot misconfigured
judge models (the design-doc §9 "zero `read` calls on a large trace"
signal in particular).
"""

import collections
import dataclasses
import logging
from typing import Any, Dict, List, Set, Tuple, Type

import pydantic

import opik
from opik.evaluation.models import base_model
from opik.decorator import context_manager

from . import context
from .tools import registry as tool_registry

LOGGER = logging.getLogger(__name__)

MAX_TOOL_CALL_ROUNDS = 10


# Rewritten from an earlier version that included an "or return your
# verdict now" off-ramp. That branch conflicted with the system prompt's
# "MUST call the indicated tool when you see a truncation hint" rule —
# weaker judge models (gpt-4o-mini class) exploited the conflict by
# duplicating tool calls to trigger this hint, then taking the
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


@opik.track(
    ignore_arguments=["model", "registry", "ctx", "response_format"],
)
def run_agentic_judge(
    *,
    model: base_model.OpikBaseModel,
    system_prompt: str,
    user_prompt: str,
    wrapup_instruction: str,
    registry: tool_registry.ToolRegistry,
    ctx: context.TraceToolContext,
    response_format: Type[pydantic.BaseModel],
    overview_truncated: bool = False,
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

    # First turn: the overview is already in the user message, so the
    # judge may either drill in via a tool call or short-circuit to the
    # final verdict when the overview alone is enough. `response_format`
    # is supplied on every turn — when the model emits content instead
    # of a tool call, that content is already structured JSON we can
    # return without a separate wrap-up round-trip.
    response = model.generate_chat_completion(
        messages=messages,
        tools=tool_specs,
        tool_choice="auto",
        response_format=response_format,
        temperature=0,
    )
    messages.append(_assistant_message_from_response(response))

    max_rounds_exhausted = True
    for round_index in range(max_rounds):
        tool_calls = response.get("tool_calls") or []
        if not tool_calls:
            max_rounds_exhausted = False
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
                # Append the tool reply unconditionally: OpenAI (and other
                # providers) reject a conversation where an assistant
                # `tool_calls` block isn't followed by a tool message for
                # every `tool_call_id`. The dedup path still needs to
                # echo *something* back so the conversation stays well-
                # formed; the hint text plays that role.
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
            response_format=response_format,
            temperature=0,
        )
        messages.append(_assistant_message_from_response(response))
    else:
        LOGGER.warning(
            "Agentic judge reached MAX_TOOL_CALL_ROUNDS=%d without terminating; "
            "forcing wrap-up.",
            max_rounds,
        )

    _emit_telemetry(telemetry, overview_truncated=overview_truncated)

    # Skip the wrap-up round-trip when the model already finalized: the
    # latest response has no pending tool calls and carries content,
    # which — thanks to `response_format` being set on every turn — is
    # already the structured JSON we'd otherwise ask the wrap-up call
    # to produce.
    pending_tool_calls = response.get("tool_calls") or []
    content = response.get("content")
    if not max_rounds_exhausted and content and not pending_tool_calls:
        return content

    # Otherwise (max rounds reached, or the last response lacked usable
    # content) force a final, tools-less, structured-output turn so the
    # caller always gets a parseable verdict to retry on.
    messages.append({"role": "user", "content": wrapup_instruction})
    verdict = model.generate_chat_completion(
        messages=messages,
        response_format=response_format,
        temperature=0,
    )
    return verdict.get("content") or ""


def _emit_telemetry(
    telemetry: LoopTelemetry,
    *,
    overview_truncated: bool,
) -> None:
    """Surface per-run counters and the "low engagement" warning.

    Two log levels:
    - INFO: rounds + tool-call breakdown. Cheap to enable, lets an
      operator see what each judge invocation cost.
    - WARNING: the inline overview was truncated (at least one field
      tripped the sizer's chosen per-field limit) AND the judge never
      called `read` to recover the hidden content. Signals the
      configured model isn't engaging with the tools — the
      `gpt-5-nano`-class failure mode the design doc §9 calls out.
      Suppressed when the overview is rendered at the no-truncation
      tier, where no-`read` is the correct outcome.
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
    if overview_truncated and telemetry.tool_calls_by_name.get("read", 0) == 0:
        read_engaged = False
        LOGGER.warning(
            "Agentic judge produced a verdict on a truncated overview "
            "without ever calling `read`. The configured judge model may "
            "not be engaging the tools — consider switching models or "
            "checking provider tool-call support."
        )

    opik.opik_context.update_current_span(
        metadata={
            "agentic_loop_rounds": telemetry.rounds,
            "agentic_loop_tool_calls": telemetry.tool_calls_by_name,
            "agentic_loop_duplicate_calls": telemetry.duplicate_calls,
            "agentic_loop_overview_truncated": overview_truncated,
            "agentic_loop_read_engaged": read_engaged,
        }
    )


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
