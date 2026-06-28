"""Regression tests for concurrent-invocation isolation in the ADK ``OpikTracer``.

A single ``OpikTracer`` instance is shared by every invocation of an instrumented
agent (the documented ``track_adk_agent_recursive`` pattern). The most recent
model output is therefore keyed by ADK ``invocation_id`` so that two invocations
in flight at the same time keep their own output. Previously it lived on a plain
instance attribute, so a concurrent invocation's ``after_model_callback`` could
overwrite it and a trace could be logged with another invocation's answer.

These tests exercise the read path (``after_agent_callback``) directly, without a
live model, by simulating interleaved invocations.
"""

import types

import pytest

from opik import context_storage
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer


def _callback_context(invocation_id: str) -> types.SimpleNamespace:
    # after_agent_callback only reads ``invocation_id`` off the callback context.
    return types.SimpleNamespace(invocation_id=invocation_id)


@pytest.fixture
def clean_context_storage():
    context_storage.clear_all()
    yield
    context_storage.clear_all()


def test_after_agent_callback__concurrent_invocations__output_is_not_mixed(
    clean_context_storage,
):
    tracer = OpikTracer(project_name="adk-test")

    # Two invocations sharing this tracer have each recorded their final model
    # output (as after_model_callback does), interleaved.
    tracer._last_model_output = {
        "invocation-A": {"text": "ALPHA"},
        "invocation-B": {"text": "BRAVO"},
    }

    # Invocation A's root agent finishes. With no span on the stack, the trace
    # path is taken.
    trace_a = TraceData(name="agent-A")
    context_storage.set_trace_data(trace_a)
    tracer.after_agent_callback(_callback_context("invocation-A"))

    # A's trace gets A's output, never B's; A's entry is cleaned up while B's is
    # left untouched for B's own after_agent_callback.
    assert trace_a.output == {"text": "ALPHA"}
    assert "invocation-A" not in tracer._last_model_output
    assert tracer._last_model_output["invocation-B"] == {"text": "BRAVO"}

    # Invocation B finishes later and still receives its own output.
    trace_b = TraceData(name="agent-B")
    context_storage.set_trace_data(trace_b)
    tracer.after_agent_callback(_callback_context("invocation-B"))

    assert trace_b.output == {"text": "BRAVO"}
    assert tracer._last_model_output == {}


def test_after_agent_callback__nested_agent__keeps_output_for_root(
    clean_context_storage,
):
    # A multi-agent invocation: sub-agents finish on the span path and must NOT
    # consume the output, because the root agent (trace path) still needs it.
    tracer = OpikTracer(project_name="adk-test")
    tracer._last_model_output = {"inv": {"text": "NESTED"}}

    trace = TraceData(name="root")
    context_storage.set_trace_data(trace)
    span = trace.create_child_span_data(name="sub-agent")
    context_storage.add_span_data(span)

    # Sub-agent finishes (span on the stack): stamp the span, keep the entry.
    tracer.after_agent_callback(_callback_context("inv"))
    assert span.output == {"text": "NESTED"}
    assert tracer._last_model_output["inv"] == {"text": "NESTED"}

    # Root agent finishes (span popped): stamp the trace and drop the entry.
    context_storage.pop_span_data()
    tracer.after_agent_callback(_callback_context("inv"))
    assert trace.output == {"text": "NESTED"}
    assert tracer._last_model_output == {}
