"""Integration test for the agentic-judge tool-call loop.

Drives the loop with a stub ChatModel that returns a canned tool-call
sequence: first turn -> `read(...)`, second turn (after tool result) ->
no more tool calls, wrap-up turn -> structured JSON verdict.

Confirms:
- `tool_choice="auto"` on the first turn (overview is pre-seeded into
  the user message, so the loop no longer forces a tool call).
- The wrap-up turn receives `response_format` and no `tools`.
- Verdict JSON is parsed into ScoreResult.
"""

import datetime
import json
from typing import Any, List, Optional, Type

import pydantic

from opik.evaluation.models import base_model
from opik.evaluation.suite_evaluators.agentic.context import TraceToolContext
from opik.evaluation.suite_evaluators.agentic.judge import AgenticLLMJudge
from opik.message_processing.emulation import (
    local_emulator_message_processor,
    models,
)


class _StubChatModel(base_model.OpikBaseModel):
    """Records every call and returns canned responses in order."""

    def __init__(self, responses: List[base_model.ConversationDict]) -> None:
        super().__init__(model_name="stub-model")
        self._responses = list(responses)
        self.calls: List[dict] = []

    def generate_string(
        self,
        input: str,
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> str:
        raise NotImplementedError

    def generate_provider_response(self, messages: List[dict], **kwargs: Any) -> Any:
        raise NotImplementedError

    def generate_chat_completion(
        self,
        messages: List[base_model.ConversationDict],
        response_format: Optional[Type[pydantic.BaseModel]] = None,
        **kwargs: Any,
    ) -> base_model.ConversationDict:
        self.calls.append(
            {
                "messages": list(messages),
                "tools": kwargs.get("tools"),
                "tool_choice": kwargs.get("tool_choice"),
                "response_format": response_format,
            }
        )
        return self._responses.pop(0)


def _build_ctx() -> TraceToolContext:
    start = datetime.datetime(2026, 5, 13, 12, 0, 0)
    trace = models.TraceModel(
        id="t-1",
        start_time=start,
        name="trace",
        project_name="default",
        source="sdk",
        input={"q": "hi"},
        output={"a": "hello"},
        end_time=start + datetime.timedelta(seconds=1),
    )
    span = models.SpanModel(
        id="s-1",
        start_time=start,
        source="sdk",
        name="tool_call",
        type="tool",
    )
    emulator = local_emulator_message_processor.LocalEmulatorMessageProcessor(
        active=True
    )
    return TraceToolContext(
        trace=trace,
        spans=[span],
        parent_by_child={"s-1": None},
        emulator=emulator,
    )


def test_score__full_loop__calls_read_and_produces_verdict():
    verdict = json.dumps(
        {
            "assertion_1": {
                "score": True,
                "reason": "tool_call span found",
                "confidence": 0.9,
            }
        }
    )
    responses: List[base_model.ConversationDict] = [
        # Turn 1 (auto): model decides to drill in via read
        {
            "role": "assistant",
            "tool_calls": [
                {
                    "id": "call-1",
                    "type": "function",
                    "function": {
                        "name": "read",
                        "arguments": '{"type": "span", "id": "s-1"}',
                    },
                }
            ],
        },
        # Turn 2 (auto): model produces no more tool calls -> exit loop
        {"role": "assistant", "content": "Looks good."},
        # Wrap-up turn: structured JSON verdict
        {"role": "assistant", "content": verdict},
    ]
    model = _StubChatModel(responses)
    judge = AgenticLLMJudge(assertions=["agent called the tool_call span"], model=model)

    results = judge.score(_build_ctx())

    assert len(results) == 1
    assert results[0].value is True
    assert results[0].reason == "tool_call span found"

    # Inspect what the loop sent the model.
    assert len(model.calls) == 3
    first, second, wrapup = model.calls
    assert first["tool_choice"] == "auto"
    assert first["tools"] and any(
        spec["function"]["name"] == "read" for spec in first["tools"]
    )
    # `get_trace_spans` is no longer in the default registry.
    assert not any(
        spec["function"]["name"] == "get_trace_spans" for spec in first["tools"]
    )
    assert second["tool_choice"] == "auto"
    # Wrap-up turn carries the response format and drops tools.
    assert wrapup["response_format"] is not None
    assert wrapup["tools"] is None


def test_score__model_loops_forever__terminates_within_max_rounds():
    """If the model keeps emitting tool calls, the loop bounds at MAX_TOOL_CALL_ROUNDS."""
    verdict = json.dumps(
        {
            "assertion_1": {
                "score": False,
                "reason": "max rounds reached",
                "confidence": 0.5,
            }
        }
    )
    looping_call: base_model.ConversationDict = {
        "role": "assistant",
        "tool_calls": [
            {
                "id": "c",
                "type": "function",
                "function": {
                    "name": "read",
                    "arguments": '{"type": "trace", "id": "t-1"}',
                },
            }
        ],
    }
    # 1 first turn + 10 follow-up turns (the loop body runs
    # MAX_TOOL_CALL_ROUNDS=10 times) + 1 wrap-up == 12 responses.
    responses: List[base_model.ConversationDict] = [looping_call] * 11 + [
        {"role": "assistant", "content": verdict}
    ]
    model = _StubChatModel(responses)
    judge = AgenticLLMJudge(assertions=["x"], model=model)

    results = judge.score(_build_ctx())

    assert results[0].value is False
    # Verify the loop didn't run away — exactly the budget was used.
    assert len(model.calls) == 12
