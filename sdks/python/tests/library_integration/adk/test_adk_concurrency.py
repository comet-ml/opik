"""Regression tests for concurrent-invocation isolation in the ADK ``OpikTracer``.

A single ``OpikTracer`` instance is shared by every invocation of an instrumented
agent (the documented ``track_adk_agent_recursive`` pattern). The most recent
model output is keyed by ADK ``invocation_id`` and dropped once an invocation's
outermost agent finishes, so concurrent invocations keep their own output.
Previously it lived on a plain instance attribute, so a concurrent invocation's
``after_model_callback`` could overwrite it and a trace could be logged with
another invocation's answer.

These tests exercise ``after_agent_callback`` directly, without a live model, by
simulating interleaved invocations. They target the modern ``OpikTracer``, which
is only used on ADK >= 1.3.0 (older ADK resolves ``OpikTracer`` to
``LegacyOpikTracer``, which manages its own trace/span lifecycle), so they are
skipped for older ADK. The autouse ``clear_context_storage`` fixture
(tests/conftest.py) resets context storage between tests.
"""

import types

import pytest

from opik import context_storage
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer

from . import helpers


def _callback_context(invocation_id: str) -> types.SimpleNamespace:
    # after_agent_callback only reads ``invocation_id`` off the callback context.
    return types.SimpleNamespace(invocation_id=invocation_id)


@pytest.fixture
def tracer() -> OpikTracer:
    return OpikTracer(project_name="adk-test")


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_agent_callback__concurrent_invocations__output_is_not_mixed(tracer):
    # Two single-agent invocations sharing this tracer have each recorded their
    # final model output (as after_model_callback does), interleaved.
    tracer._last_model_output = {
        "invocation-A": {"text": "ALPHA"},
        "invocation-B": {"text": "BRAVO"},
    }
    tracer._open_agents = {"invocation-A": 1, "invocation-B": 1}

    # Invocation A's (root) agent finishes — no span on the stack, trace path.
    trace_a = TraceData(name="agent-A")
    context_storage.set_trace_data(trace_a)
    tracer.after_agent_callback(_callback_context("invocation-A"))

    # A's trace gets A's output, never B's; A's entry is dropped, B's is kept.
    assert trace_a.output == {"text": "ALPHA"}
    assert "invocation-A" not in tracer._last_model_output
    assert tracer._last_model_output["invocation-B"] == {"text": "BRAVO"}

    # Invocation B finishes later and still receives its own output.
    trace_b = TraceData(name="agent-B")
    context_storage.set_trace_data(trace_b)
    tracer.after_agent_callback(_callback_context("invocation-B"))

    assert trace_b.output == {"text": "BRAVO"}
    assert tracer._last_model_output == {}
    assert tracer._open_agents == {}


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_agent_callback__nested_agent__keeps_output_until_root_finishes(tracer):
    # A multi-agent invocation: a sub-agent finishes on the span path and must
    # NOT consume the output, because the root agent still needs it.
    tracer._last_model_output = {"inv": {"text": "NESTED"}}
    tracer._open_agents = {"inv": 2}  # root + one sub-agent open

    trace = TraceData(name="root")
    context_storage.set_trace_data(trace)
    span = trace.create_child_span_data(name="sub-agent")
    context_storage.add_span_data(span)

    # Sub-agent finishes (span on the stack): stamp the span, keep the entry.
    tracer.after_agent_callback(_callback_context("inv"))
    assert span.output == {"text": "NESTED"}
    assert tracer._last_model_output["inv"] == {"text": "NESTED"}
    assert tracer._open_agents["inv"] == 1

    # Root agent finishes (span popped): stamp the trace, drop the entry.
    context_storage.pop_span_data()
    tracer.after_agent_callback(_callback_context("inv"))
    assert trace.output == {"text": "NESTED"}
    assert tracer._last_model_output == {}
    assert tracer._open_agents == {}


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_agent_callback__distributed_root_is_a_span__output_is_dropped(tracer):
    # Under distributed_headers the root agent is itself a span (not a trace), so
    # cleanup must not rely on the trace path — otherwise the cached output would
    # leak. Here the root finishes on the span path and the entry is still dropped.
    tracer._last_model_output = {"inv": {"text": "DIST"}}
    tracer._open_agents = {"inv": 1}

    trace = TraceData(name="distributed-trace")
    context_storage.set_trace_data(trace)
    root_span = trace.create_child_span_data(name="root-agent")
    context_storage.add_span_data(root_span)

    tracer.after_agent_callback(_callback_context("inv"))

    assert root_span.output == {"text": "DIST"}
    assert tracer._last_model_output == {}
    assert tracer._open_agents == {}
