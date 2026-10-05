"""Regression tests for OPIK-8574: ADK ends its ``generate_content`` inference span
as soon as it records the final model response, before running
``after_model_callback``. The Opik OTel wrapper adopts the LLM span created in
``before_model_callback`` into that inference span, so finalizing it on the span's
exit sent the LLM span without output and usage. That copy raced the complete one
``after_model_callback`` sends, and the backend sometimes kept the empty one.
"""

import contextvars
import types
from typing import Any, Dict, List
from unittest import mock

import google.adk.telemetry as adk_telemetry
import pytest
from google.adk import models
from google.genai import types as genai_types

from opik import context_storage
from opik.api_objects import opik_client
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer

from . import helpers


pytestmark = helpers.pytest_skip_for_adk_older_than_1_3_0

MODEL = "gemini-2.0-flash"


@pytest.fixture
def sent_spans(fake_backend) -> List[Dict[str, Any]]:
    """Every span the Opik client is asked to send, as its keyword arguments."""
    calls: List[Dict[str, Any]] = []
    original = opik_client.Opik.__internal_api__span__

    def record(self, **kwargs):
        calls.append(kwargs)
        return original(self, **kwargs)

    with mock.patch.object(
        opik_client.Opik, "__internal_api__span__", autospec=True, side_effect=record
    ):
        yield calls


def _sends_of(sent_spans: List[Dict[str, Any]], span_id: str) -> List[Dict[str, Any]]:
    return [call for call in sent_spans if call["id"] == span_id]


def _start_model_call(tracer: OpikTracer, ctx: object):
    tracer.before_model_callback(ctx, models.LlmRequest(model=MODEL))
    return context_storage.top_span_data()


def _final_response_with_usage() -> models.LlmResponse:
    return models.LlmResponse(
        content=genai_types.Content(role="model", parts=[genai_types.Part(text="hi")]),
        partial=False,
        usage_metadata=genai_types.GenerateContentResponseUsageMetadata(
            prompt_token_count=10, candidates_token_count=5, total_token_count=15
        ),
    )


def test_inference_span__ends_before_after_model__llm_span_sent_once_with_usage(
    sent_spans,
):
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())
    llm_span = _start_model_call(tracer, ctx)

    with adk_telemetry.tracer.start_as_current_span(f"generate_content {MODEL}"):
        pass

    assert _sends_of(sent_spans, llm_span.id) == []
    assert context_storage.top_span_data() is llm_span

    tracer.after_model_callback(ctx, _final_response_with_usage())

    sends = _sends_of(sent_spans, llm_span.id)
    assert len(sends) == 1
    assert sends[0]["end_time"] is not None
    assert sends[0]["output"]["content"]["parts"][0]["text"] == "hi"
    usage = sends[0]["usage"].to_backend_compatible_full_usage_dict()
    assert usage["prompt_tokens"] == 10
    assert usage["completion_tokens"] == 5
    assert usage["total_tokens"] == 15
    assert context_storage.top_span_data() is None


def test_inference_span__model_error__llm_span_sent_with_error_info(sent_spans):
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())
    llm_span = _start_model_call(tracer, ctx)

    with pytest.raises(ValueError):
        with adk_telemetry.tracer.start_as_current_span(f"generate_content {MODEL}"):
            raise ValueError("model failed")

    sends = _sends_of(sent_spans, llm_span.id)
    assert len(sends) == 1
    assert sends[0]["end_time"] is not None
    assert sends[0]["error_info"]["exception_type"] == "ValueError"
    assert "model failed" in sends[0]["error_info"]["message"]
    assert context_storage.top_span_data() is None


def test_inference_span__after_model_callback_skipped__llm_span_sent_at_next_span(
    sent_spans,
):
    # ADK skips the agent's after_model_callbacks when a plugin returns a response.
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())
    llm_span = _start_model_call(tracer, ctx)

    with adk_telemetry.tracer.start_as_current_span(f"generate_content {MODEL}"):
        pass
    with adk_telemetry.tracer.start_as_current_span("execute_tool get_weather"):
        tool_span = context_storage.top_span_data()

    assert tool_span is not llm_span
    sends = _sends_of(sent_spans, llm_span.id)
    assert len(sends) == 1
    assert sends[0]["end_time"] is not None
    assert context_storage.top_span_data() is None


def test_inference_span__after_model_callback_skipped__llm_span_sent_when_trace_ends(
    sent_spans,
):
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())

    with adk_telemetry.tracer.start_as_current_span("invoke_agent weather_agent"):
        llm_span = _start_model_call(tracer, ctx)
        with adk_telemetry.tracer.start_as_current_span(f"generate_content {MODEL}"):
            pass

    assert len(_sends_of(sent_spans, llm_span.id)) == 1
    assert context_storage.top_span_data() is None


def test_after_model__detached_context__llm_span_not_sent_again_at_next_span(
    sent_spans,
):
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())
    llm_span = _start_model_call(tracer, ctx)

    # after_model_callback runs in a detached context that doesn't hold the span,
    # so the original context's stack keeps the already-finalized span.
    def run_detached_after_model_callback() -> None:
        context_storage.pop_span_data()
        tracer.after_model_callback(ctx, _final_response_with_usage())

    contextvars.copy_context().run(run_detached_after_model_callback)
    # Don't read the span stack here: the context storage getters discard finished
    # spans as a side effect, which would bypass the next-span path under test.
    assert llm_span.end_time is not None

    with adk_telemetry.tracer.start_as_current_span("execute_tool get_weather"):
        pass

    assert len(_sends_of(sent_spans, llm_span.id)) == 1
    assert context_storage.top_span_data() is None
