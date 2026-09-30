"""Regression tests for OPIK-8574: ADK ends its ``generate_content`` inference span
as soon as it records the final model response, before running
``after_model_callback``. The Opik OTel wrapper adopts the LLM span created in
``before_model_callback`` into that inference span, so finalizing it on the span's
exit sent the LLM span without output and usage. That copy raced the complete one
``after_model_callback`` sends, and the backend sometimes kept the empty one.
"""

import types

import google.adk.telemetry as adk_telemetry
import pytest
from google.adk import models
from google.genai import types as genai_types

from opik import context_storage
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer

from . import helpers


pytestmark = helpers.pytest_skip_for_adk_older_than_1_3_0

MODEL = "gemini-2.0-flash"


def _final_response_with_usage() -> models.LlmResponse:
    return models.LlmResponse(
        content=genai_types.Content(role="model", parts=[genai_types.Part(text="hi")]),
        partial=False,
        usage_metadata=genai_types.GenerateContentResponseUsageMetadata(
            prompt_token_count=10, candidates_token_count=5, total_token_count=15
        ),
    )


def test_inference_span__ends_before_after_model__llm_span_finalized_once_with_usage(
    fake_backend,
):
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())
    tracer.before_model_callback(ctx, models.LlmRequest(model=MODEL))
    llm_span = context_storage.top_span_data()

    with adk_telemetry.tracer.start_as_current_span(f"generate_content {MODEL}"):
        pass

    assert llm_span.end_time is None
    assert context_storage.top_span_data() is llm_span

    tracer.after_model_callback(ctx, _final_response_with_usage())

    assert llm_span.end_time is not None
    assert llm_span.output is not None
    assert llm_span.usage is not None
    assert context_storage.top_span_data() is None


def test_inference_span__model_error__llm_span_finalized_with_error_info(
    fake_backend,
):
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1", actions=object())
    tracer.before_model_callback(ctx, models.LlmRequest(model=MODEL))
    llm_span = context_storage.top_span_data()

    with pytest.raises(ValueError):
        with adk_telemetry.tracer.start_as_current_span(f"generate_content {MODEL}"):
            raise ValueError("model failed")

    assert llm_span.end_time is not None
    assert llm_span.error_info is not None
    assert context_storage.top_span_data() is None
