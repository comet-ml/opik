"""Tool-call loop driver for the agentic LLM judge.

Mirrors the backend `OnlineScoringLlmAsJudgeScorer` protocol:
- First call forces `tool_choice="required"` so the judge MUST call
  `get_trace_spans` (or any later tool) and see the trace before forming
  a verdict.
- Subsequent calls flip to `tool_choice="auto"`.
- A final, no-tool, structured-output turn produces the JSON verdict the
  caller parses.
"""

import logging
from typing import Any, List, Type

import pydantic

from opik.evaluation.models import base_model

from . import context
from .tools import registry as tool_registry

LOGGER = logging.getLogger(__name__)

MAX_TOOL_CALL_ROUNDS = 10


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

        for call in tool_calls:
            tool_name = call["function"]["name"]
            arguments = call["function"].get("arguments", "{}")
            result = registry.execute(tool_name, arguments, ctx)
            messages.append(
                {
                    "role": "tool",
                    "tool_call_id": call["id"],
                    "content": result,
                }
            )

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
