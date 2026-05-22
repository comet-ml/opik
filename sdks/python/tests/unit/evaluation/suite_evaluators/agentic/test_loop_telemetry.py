"""Tests for the agentic loop's telemetry emission.

Covers the design-doc §9 signals: per-run round counts, per-tool call
counts, and the "judge returned a verdict on a large trace without
calling `read`" warning. Drives the loop with a stub model so we don't
hit the network and can dictate the tool-call sequence directly.
"""

import datetime
import logging
from typing import Any, List, Optional, Type

import pydantic
import pytest

from opik.evaluation.models import base_model
from opik.evaluation.suite_evaluators.agentic import loop
from opik.evaluation.suite_evaluators.agentic.tools import registry as tool_registry
from opik.message_processing.emulation import models

from . import _seeding


class _CapturingHandler(logging.Handler):
    """Drop-in handler that buffers records — opik configures its
    logger with `propagate=False`, so pytest's caplog (which sits on the
    root logger) doesn't see anything emitted under `opik.*`. Attaching
    this handler directly to `loop.LOGGER` works around that without
    touching opik's logging configuration.
    """

    def __init__(self) -> None:
        super().__init__()
        self.records: List[logging.LogRecord] = []

    def emit(self, record: logging.LogRecord) -> None:
        self.records.append(record)


@pytest.fixture
def loop_log_records():
    handler = _CapturingHandler()
    handler.setLevel(logging.DEBUG)
    previous_level = loop.LOGGER.level
    loop.LOGGER.setLevel(logging.DEBUG)
    loop.LOGGER.addHandler(handler)
    try:
        yield handler.records
    finally:
        loop.LOGGER.removeHandler(handler)
        loop.LOGGER.setLevel(previous_level)


class _StubChatModel(base_model.OpikBaseModel):
    """Returns canned responses in order; records every call."""

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
                "messages": [dict(m) for m in messages],
                "tool_choice": kwargs.get("tool_choice"),
                "response_format": response_format,
            }
        )
        return self._responses.pop(0)


class _StubTool:
    """Minimal ToolExecutor for the registry under test."""

    def __init__(self, name: str, payload: str = "{}") -> None:
        self.name = name
        self.spec = {"type": "function", "function": {"name": name}}
        self._payload = payload
        self.execute_count = 0

    def execute(self, arguments, ctx):
        self.execute_count += 1
        return self._payload


class _WrapupSchema(pydantic.BaseModel):
    verdict: str


def _trace(input_payload=None) -> models.TraceModel:
    return models.TraceModel(
        id="t-1",
        start_time=datetime.datetime(2026, 5, 13),
        name="trace",
        project_name="default",
        source="sdk",
        input=input_payload or {"q": "hi"},
        output={"a": "ok"},
        end_time=datetime.datetime(2026, 5, 13, 0, 0, 1),
    )


def _ctx(trace: models.TraceModel):
    return _seeding.build_ctx(trace, [])


def _tool_call(tool_id: str, name: str, arguments: str = "{}") -> dict:
    return {
        "id": tool_id,
        "function": {"name": name, "arguments": arguments},
    }


def _run_with_responses(responses, tools, ctx, overview_truncated=False):
    model = _StubChatModel(responses)
    registry = tool_registry.ToolRegistry(tools=tools)
    content = loop.run_agentic_judge(
        model=model,
        system_prompt="sys",
        user_prompt="user",
        wrapup_instruction="wrap",
        registry=registry,
        ctx=ctx,
        response_format=_WrapupSchema,
        overview_truncated=overview_truncated,
    )
    return content, model, tools


def _conversation_is_well_formed(messages: List[dict]) -> bool:
    """Every assistant `tool_calls` block must be followed by one tool
    message per `tool_call_id`. OpenAI rejects (400) otherwise.
    """
    i = 0
    while i < len(messages):
        msg = messages[i]
        if msg.get("role") == "assistant" and msg.get("tool_calls"):
            expected_ids = {call["id"] for call in msg["tool_calls"]}
            seen_ids = set()
            j = i + 1
            while j < len(messages) and messages[j].get("role") == "tool":
                seen_ids.add(messages[j]["tool_call_id"])
                j += 1
            if seen_ids != expected_ids:
                return False
            i = j
        else:
            i += 1
    return True


class TestTelemetryLogging:
    def test_run_agentic_judge__single_round__logs_round_and_tool_counts(
        self, loop_log_records
    ):
        # Sequence:
        #  1) first turn (auto) → calls scan once
        #  2) auto turn → no tool calls (loop ends)
        #  3) wrap-up → JSON verdict
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(responses, [_StubTool("scan")], ctx)

        finished = [
            r for r in loop_log_records if "Agentic loop finished" in r.getMessage()
        ]
        assert len(finished) == 1
        message = finished[0].getMessage()
        assert "rounds=1" in message
        assert "scan=1" in message

    def test_run_agentic_judge__multi_tool_round__counts_per_tool(
        self, loop_log_records
    ):
        # One round, two tool calls in parallel: read + scan.
        responses = [
            {
                "tool_calls": [
                    _tool_call("c1", "read"),
                    _tool_call("c2", "scan"),
                ],
            },
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(responses, [_StubTool("read"), _StubTool("scan")], ctx)

        finished = [
            r for r in loop_log_records if "Agentic loop finished" in r.getMessage()
        ]
        assert len(finished) == 1
        message = finished[0].getMessage()
        # Names are sorted alphabetically for stable rendering.
        assert "rounds=1" in message
        assert "read=1, scan=1" in message


class TestZeroReadOnTruncatedOverviewWarning:
    """The "low engagement" warning fires when the inline overview was
    truncated AND the judge never called `read` — a signal the model
    isn't following the truncation hints. Suppressed when the overview
    was rendered at the no-truncation tier (no-`read` is correct) or
    when the model did call `read` at least once.
    """

    def test_run_agentic_judge__zero_read_with_truncated_overview__warns(
        self, loop_log_records
    ):
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(
            responses, [_StubTool("scan")], ctx, overview_truncated=True
        )

        warnings = [
            r
            for r in loop_log_records
            if r.levelno >= logging.WARNING
            and "without ever calling `read`" in r.getMessage()
        ]
        assert len(warnings) == 1

    def test_run_agentic_judge__zero_read_with_full_overview__does_not_warn(
        self, loop_log_records
    ):
        # The sizer picked the no-truncation tier, so no `read` is the
        # correct outcome — warning would be a false positive.
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(
            responses, [_StubTool("scan")], ctx, overview_truncated=False
        )

        warnings = [
            r
            for r in loop_log_records
            if r.levelno >= logging.WARNING
            and "without ever calling `read`" in r.getMessage()
        ]
        assert warnings == []

    def test_run_agentic_judge__read_called_with_truncated_overview__does_not_warn(
        self, loop_log_records
    ):
        # Truncated overview BUT the judge did call `read` once. No warning.
        responses = [
            {"tool_calls": [_tool_call("c1", "read")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(
            responses,
            [_StubTool("read", payload='{"data": {}}')],
            ctx,
            overview_truncated=True,
        )

        warnings = [
            r
            for r in loop_log_records
            if r.levelno >= logging.WARNING
            and "without ever calling `read`" in r.getMessage()
        ]
        assert warnings == []


class TestDuplicateToolCallShortCircuit:
    """The loop short-circuits identical (name, args) tool calls — same
    args returns a dedup hint instead of re-executing the tool. This is
    the deterministic fix for the failure mode where a judge model loops
    on the same tool/args instead of drilling in (design doc §9; PR
    review transcript on OPIK-6243)."""

    def test_run_agentic_judge__dedup_path__still_appends_tool_message(self):
        """Regression: every assistant `tool_calls` block must be followed
        by a tool reply for every `tool_call_id`, even when the loop
        short-circuits a duplicate call. Without this, real providers
        (e.g., OpenAI) reject the next request with a 400.
        """
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"tool_calls": [_tool_call("c2", "scan")]},
            {"content": '{"verdict": "ok"}', "tool_calls": []},
        ]
        ctx = _ctx(_trace())
        tool = _StubTool("scan", payload='{"spans": []}')

        _, model, _ = _run_with_responses(responses, [tool], ctx)

        # Final assistant→tool pairing in the conversation sent on every
        # turn must be well-formed.
        for call in model.calls:
            assert _conversation_is_well_formed(call["messages"]), (
                f"Conversation has an unanswered tool_call:\n{call['messages']}"
            )

    def test_run_agentic_judge__repeated_identical_call__tool_executes_once(
        self,
    ):
        # Two rounds, both call scan({}); the second should
        # be short-circuited and the tool should execute only once.
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"tool_calls": [_tool_call("c2", "scan")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())
        tool = _StubTool("scan", payload='{"spans": []}')

        _run_with_responses(responses, [tool], ctx)

        assert tool.execute_count == 1

    def test_run_agentic_judge__different_arguments__both_execute(self):
        # Same tool, different arguments → not a duplicate; both execute.
        responses = [
            {
                "tool_calls": [
                    _tool_call("c1", "read", arguments='{"type": "trace", "id": "a"}'),
                ]
            },
            {
                "tool_calls": [
                    _tool_call("c2", "read", arguments='{"type": "trace", "id": "b"}'),
                ]
            },
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())
        tool = _StubTool("read", payload='{"data": {}}')

        _run_with_responses(responses, [tool], ctx)

        assert tool.execute_count == 2

    def test_run_agentic_judge__duplicate_call__telemetry_reports_count(
        self, loop_log_records
    ):
        # By-name histogram counts both call attempts (the model emitted
        # them); the separate `duplicates=N` field reports how many were
        # short-circuited. Asserting on both keeps the contract explicit.
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"tool_calls": [_tool_call("c2", "scan")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(responses, [_StubTool("scan")], ctx)

        finished = [
            r for r in loop_log_records if "Agentic loop finished" in r.getMessage()
        ]
        assert len(finished) == 1
        message = finished[0].getMessage()
        assert "scan=2" in message
        assert "duplicates=1" in message

    def test_run_agentic_judge__no_duplicates__telemetry_reports_zero(
        self, loop_log_records
    ):
        # Sanity: when nothing is deduped, the duplicates counter is 0.
        responses = [
            {"tool_calls": [_tool_call("c1", "scan")]},
            {"content": "", "tool_calls": []},
            {"content": '{"verdict": "ok"}'},
        ]
        ctx = _ctx(_trace())

        _run_with_responses(responses, [_StubTool("scan")], ctx)

        finished = [
            r for r in loop_log_records if "Agentic loop finished" in r.getMessage()
        ]
        assert "duplicates=0" in finished[0].getMessage()
