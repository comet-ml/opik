"""
Tests for OPIK-6389: ADK OTel patching delegates to the original tracer instead
of suppressing spans, and is idempotent across re-patching (deep-copy /
``__setstate__``).
"""

import asyncio
from typing import AsyncGenerator

import google.adk.telemetry as adk_telemetry
import pytest
from google.adk import agents as adk_agents
from google.adk import runners as adk_runners
from google.adk.models import base_llm, llm_request, llm_response
from google.adk.sessions import in_memory_session_service
from google.genai import types as genai_types
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import (
    InMemorySpanExporter,
)
from opentelemetry.sdk.trace.sampling import ALWAYS_ON

import opik
from opik.integrations.adk import OpikTracer
from opik.integrations.adk.patchers import patchers
from opik.integrations.adk.patchers.adk_otel_tracer.opik_adk_otel_tracer import (
    OpikADKOtelTracer,
)
from . import helpers
from ...testlib import ANY_BUT_NONE, ANY_STRING, TraceModel, assert_equal


pytestmark = helpers.pytest_skip_for_adk_older_than_1_3_0


@pytest.fixture(scope="module")
def otel_exporter() -> InMemorySpanExporter:
    """Install a real TracerProvider with an in-memory exporter once per module.

    ``set_tracer_provider`` is a global one-shot in OpenTelemetry; doing it
    per-test would trigger a warning and be ignored. Tests clear the exporter
    before consuming spans rather than recreating the provider.
    """
    exporter = InMemorySpanExporter()
    provider = TracerProvider(sampler=ALWAYS_ON)
    provider.add_span_processor(SimpleSpanProcessor(exporter))
    trace.set_tracer_provider(provider)
    return exporter


@pytest.fixture
def opik_tracer_with_otel(otel_exporter: InMemorySpanExporter):
    """Create an OpikTracer (triggers ADK OTel patching) and clear the exporter.

    The ADK tracer singleton is shared across the test session; the Opik
    wrapper, once installed, is intentionally idempotent on re-patching. Tests
    therefore only need a clean exporter between runs.
    """
    tracer = OpikTracer(name="test-otel-delegation")
    otel_exporter.clear()
    yield tracer


def test_adk_otel_patching__wrapper_attached_to_singleton__happyflow(
    opik_tracer_with_otel,
):
    wrapper = getattr(adk_telemetry.tracer, "_opik_wrapper", None)
    assert isinstance(wrapper, OpikADKOtelTracer)


def test_adk_otel_patching__start_as_current_span__yields_recording_span(
    opik_tracer_with_otel, otel_exporter
):
    with adk_telemetry.tracer.start_as_current_span("delegation_test_span") as span:
        assert span.is_recording(), (
            "Expected a recording span yielded by the inner tracer, "
            "got a NoOp / INVALID_SPAN — delegation is broken."
        )

    exported_names = [s.name for s in otel_exporter.get_finished_spans()]
    assert "delegation_test_span" in exported_names


def test_adk_otel_patching__skip_listed_span_name__still_delegated_to_otel(
    opik_tracer_with_otel, otel_exporter
):
    """Skip-listed ADK-internal spans (`invocation`, `call_llm`) bypass Opik
    bookkeeping but must still reach user-configured OTel exporters."""
    with adk_telemetry.tracer.start_as_current_span("invocation") as span:
        assert span.is_recording()

    exported_names = [s.name for s in otel_exporter.get_finished_spans()]
    assert "invocation" in exported_names


def test_adk_otel_patching__re_patching__is_idempotent(opik_tracer_with_otel):
    """Simulates the ``__setstate__`` path that fires on every
    ``model_copy(deep=True)`` of an agent — must not re-wrap the tracer."""
    wrapper_before = adk_telemetry.tracer._opik_wrapper
    patchers.patch_adk(distributed_headers=None)
    wrapper_after = adk_telemetry.tracer._opik_wrapper

    assert wrapper_before is wrapper_after


def test_adk_otel_patching__re_patching__refreshes_distributed_headers(
    opik_tracer_with_otel,
):
    """Even though re-patching is a no-op, ``distributed_headers`` must still
    propagate from the freshly constructed OpikTracer to the live wrapper —
    matches the intent of the prior ``a70c4afe7`` fix."""
    new_headers = {
        "opik_parent_span_id": "test-parent",
        "opik_trace_id": "test-trace",
    }
    patchers.patch_adk(distributed_headers=new_headers)

    assert adk_telemetry.tracer._opik_wrapper._distributed_headers == new_headers


def test_adk_otel_patching__nested_spans__parent_child_relationship_preserved(
    opik_tracer_with_otel, otel_exporter
):
    """The inner tracer's context propagation must remain intact: a child span
    opened inside a parent must record the parent's span_id."""
    with adk_telemetry.tracer.start_as_current_span("parent_span") as parent:
        with adk_telemetry.tracer.start_as_current_span("child_span") as child:
            assert child.is_recording()
            assert (
                child.get_span_context().trace_id == parent.get_span_context().trace_id
            )
            child_parent_id = child.parent.span_id if child.parent else None
            assert child_parent_id == parent.get_span_context().span_id

    exported_names = sorted(s.name for s in otel_exporter.get_finished_spans())
    assert exported_names == ["child_span", "parent_span"]


def test_adk_otel_patching__start_span_then_end__opik_trace_logged_and_otel_span_exported(
    opik_tracer_with_otel, otel_exporter, fake_backend
):
    """google-adk >= 2.12.0 opens `invoke_agent ...` with start_span and ends it
    explicitly, so the Opik trace has to follow that lifecycle."""
    span = adk_telemetry.tracer.start_span("invoke_agent weather_agent")
    assert span.is_recording()
    assert opik.context_storage.get_trace_data() is not None
    span.end()
    opik_tracer_with_otel.flush()

    assert opik.context_storage.get_trace_data() is None
    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        TraceModel(
            id=ANY_BUT_NONE,
            name="invoke_agent weather_agent",
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            last_updated_at=ANY_BUT_NONE,
            spans=[],
        ),
        fake_backend.trace_trees[0],
    )
    exported_names = [s.name for s in otel_exporter.get_finished_spans()]
    assert "invoke_agent weather_agent" in exported_names


def test_adk_otel_patching__start_span__child_span_parented_to_returned_span(
    opik_tracer_with_otel, fake_backend
):
    """ADK puts the span returned by start_span into the OTel context, so OTel
    children must parent to the real span and Opik children must nest under
    the Opik trace."""
    parent = adk_telemetry.tracer.start_span("invoke_agent weather_agent")
    with adk_telemetry.tracer.start_as_current_span(
        "execute_tool get_weather",
        context=trace.set_span_in_context(parent),
    ) as child:
        assert child.parent.span_id == parent.get_span_context().span_id
    parent.end()
    opik_tracer_with_otel.flush()

    assert len(fake_backend.trace_trees) == 1
    assert [s.name for s in fake_backend.trace_trees[0].spans] == [
        "execute_tool get_weather"
    ]


def test_adk_otel_patching__start_span__recorded_exception__logged_as_error_info(
    opik_tracer_with_otel, fake_backend
):
    span = adk_telemetry.tracer.start_span("invoke_agent weather_agent")
    span.record_exception(ZeroDivisionError("boom"))
    span.end()
    opik_tracer_with_otel.flush()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].error_info == {
        "exception_type": "ZeroDivisionError",
        "message": ANY_STRING,
        "traceback": ANY_STRING,
    }


def test_adk_otel_patching__start_span__ended_twice__opik_trace_logged_once(
    opik_tracer_with_otel, fake_backend
):
    span = adk_telemetry.tracer.start_span("invoke_agent weather_agent")
    span.end()
    span.end()
    opik_tracer_with_otel.flush()

    assert len(fake_backend.trace_trees) == 1


def test_adk_otel_patching__start_span__inner_start_span_raises__no_opik_trace_left_open(
    opik_tracer_with_otel,
):
    def failing_start_span(*args, **kwargs):
        raise RuntimeError("inner start_span failed")

    wrapper = OpikADKOtelTracer(
        inner_start_as_current_span=adk_telemetry.tracer.start_as_current_span,
        inner_start_span=failing_start_span,
        distributed_headers=None,
    )

    with pytest.raises(RuntimeError):
        wrapper.start_span("invoke_agent weather_agent")

    assert opik.context_storage.get_trace_data() is None
    assert opik.context_storage.top_span_data() is None


def test_adk_otel_patching__real_sdk_tracer__each_span_tracked_once(
    opik_tracer_with_otel, fake_backend
):
    """Under `adk api_server`, google-adk >= 2.12.0 imports its tracing module
    lazily, after the server installs a TracerProvider, so ADK's tracer is a real
    SDK Tracer rather than a ProxyTracer. The SDK Tracer implements
    start_as_current_span by calling self.start_span, which is the patched
    method, so each span must still get Opik bookkeeping only once."""
    sdk_tracer = TracerProvider(sampler=ALWAYS_ON).get_tracer("adk-sdk-tracer-test")
    wrapper = OpikADKOtelTracer(
        inner_start_as_current_span=sdk_tracer.start_as_current_span,
        inner_start_span=sdk_tracer.start_span,
        distributed_headers=None,
    )
    sdk_tracer.start_as_current_span = wrapper.start_as_current_span
    sdk_tracer.start_span = wrapper.start_span

    agent_span = sdk_tracer.start_span("invoke_agent weather_agent")
    with sdk_tracer.start_as_current_span("execute_tool get_weather"):
        pass
    agent_span.end()
    opik_tracer_with_otel.flush()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.name == "invoke_agent weather_agent"
    assert [s.name for s in trace_tree.spans] == ["execute_tool get_weather"]
    assert trace_tree.spans[0].spans == []


def test_adk_otel_patching__start_span_skip_listed_name__no_opik_bookkeeping(
    opik_tracer_with_otel, otel_exporter, fake_backend
):
    span = adk_telemetry.tracer.start_span("invocation")
    assert opik.context_storage.get_trace_data() is None
    span.end()
    opik_tracer_with_otel.flush()

    assert fake_backend.trace_trees == []
    exported_names = [s.name for s in otel_exporter.get_finished_spans()]
    assert "invocation" in exported_names


class _FailingModel(base_llm.BaseLlm):
    model: str = "fake-gemini"

    async def generate_content_async(
        self, request: llm_request.LlmRequest, stream: bool = False
    ) -> AsyncGenerator[llm_response.LlmResponse, None]:
        raise RuntimeError("model boom")
        yield  # makes this an async generator, as ADK expects


def test_adk_otel_patching__agent_fails_without_tracer_provider__trace_has_error_info(
    opik_tracer_with_otel, monkeypatch, fake_backend
):
    """Without an app-installed TracerProvider ADK's spans are NonRecordingSpans,
    and google-adk >= 2.12.0 records an escaping exception only when
    span.is_recording() is true. The Opik trace must still get the error.

    The module fixture installs a global TracerProvider, so the inner tracer is
    swapped for a no-op one to reproduce the Opik-only setup."""
    no_op_tracer = trace.NoOpTracer()
    wrapper = OpikADKOtelTracer(
        inner_start_as_current_span=no_op_tracer.start_as_current_span,
        inner_start_span=no_op_tracer.start_span,
        distributed_headers=None,
    )
    monkeypatch.setattr(
        adk_telemetry.tracer, "start_as_current_span", wrapper.start_as_current_span
    )
    monkeypatch.setattr(adk_telemetry.tracer, "start_span", wrapper.start_span)

    agent = adk_agents.LlmAgent(
        name="weather_agent",
        model=_FailingModel(),
        instruction="Answer the weather question.",
        before_agent_callback=opik_tracer_with_otel.before_agent_callback,
        after_agent_callback=opik_tracer_with_otel.after_agent_callback,
        before_model_callback=opik_tracer_with_otel.before_model_callback,
        after_model_callback=opik_tracer_with_otel.after_model_callback,
    )
    session_service = in_memory_session_service.InMemorySessionService()
    runner = adk_runners.Runner(
        agent=agent, app_name="error-probe", session_service=session_service
    )

    async def _run() -> None:
        await session_service.create_session(
            app_name="error-probe", user_id="u1", session_id="s1"
        )
        async for _ in runner.run_async(
            user_id="u1",
            session_id="s1",
            new_message=genai_types.Content(
                role="user", parts=[genai_types.Part(text="weather?")]
            ),
        ):
            pass

    with pytest.raises(RuntimeError, match="model boom"):
        asyncio.run(_run())
    opik_tracer_with_otel.flush()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].error_info == {
        "exception_type": "RuntimeError",
        "message": ANY_STRING,
        "traceback": ANY_STRING,
    }
