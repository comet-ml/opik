"""
Tests for OPIK-6389: ADK OTel patching delegates to the original tracer instead
of suppressing spans, and is idempotent across re-patching (deep-copy /
``__setstate__``).
"""

import google.adk.telemetry as adk_telemetry
import pytest
from opentelemetry import trace
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import (
    InMemorySpanExporter,
)
from opentelemetry.sdk.trace.sampling import ALWAYS_ON

from opik.integrations.adk import OpikTracer
from opik.integrations.adk.patchers import patchers
from opik.integrations.adk.patchers.adk_otel_tracer.opik_adk_otel_tracer import (
    OpikADKOtelTracer,
)
from . import helpers


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
