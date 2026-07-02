"""Regression tests for the ADK OpikTracer's per-invocation model-output cache.

A single ``OpikTracer`` is shared across every invocation of an instrumented
agent (the documented ``track_adk_agent_recursive`` pattern). The last model
output is cached per ADK ``invocation_id`` (``LastModelOutputCache``) so
concurrent invocations don't overwrite each other's output, and the cache is
size-bounded so it can't grow without bound when an invocation's
``after_agent_callback`` never runs (agent errors, early escalation,
cancellation).

The cache is unit-tested directly; ``after_agent_callback`` is exercised against
the modern ``OpikTracer`` (skipped on ADK < 1.3.0, which uses
``LegacyOpikTracer``). The autouse ``clear_context_storage`` fixture
(tests/conftest.py) resets context storage between tests.
"""

import types

from google.adk.models import LlmResponse
from google.genai import types as genai_types
from opik import context_storage
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer
from opik.integrations.adk.output_cache import LastModelOutputCache

from . import helpers


def _callback_context(invocation_id: str) -> types.SimpleNamespace:
    # after_agent_callback only reads ``invocation_id`` off the callback context.
    return types.SimpleNamespace(invocation_id=invocation_id)


def test_last_model_output_cache__isolates_by_invocation_id():
    cache = LastModelOutputCache()

    cache.set("inv-A", {"text": "ALPHA"})
    cache.set("inv-B", {"text": "BRAVO"})  # would clobber a single shared slot

    assert cache.get("inv-A") == {"text": "ALPHA"}
    assert cache.get("inv-B") == {"text": "BRAVO"}
    assert cache.get("missing") is None


def test_last_model_output_cache__evicts_oldest_when_full():
    cache = LastModelOutputCache(max_size=2)

    cache.set("inv-1", {"n": 1})
    cache.set("inv-2", {"n": 2})
    cache.set("inv-3", {"n": 3})  # over capacity -> evicts the oldest (inv-1)

    assert cache.get("inv-1") is None  # bounded: abandoned entry evicted
    assert cache.get("inv-2") == {"n": 2}
    assert cache.get("inv-3") == {"n": 3}


def test_last_model_output_cache__discard_removes_entry():
    cache = LastModelOutputCache()
    cache.set("inv", {"text": "OLD"})

    cache.discard("inv")

    assert cache.get("inv") is None
    cache.discard("inv")  # idempotent
    cache.discard("missing")  # missing key is a no-op


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_agent_callback__stamps_only_its_own_invocations_output():
    tracer = OpikTracer(project_name="adk-test")

    # Two concurrent invocations recorded their model output on the shared tracer
    # (as after_model_callback does), interleaved.
    tracer._last_model_output.set("invocation-A", {"text": "ALPHA"})
    tracer._last_model_output.set("invocation-B", {"text": "BRAVO"})

    # Invocation A's agent finishes — no span on the stack, so the trace path.
    trace_a = TraceData(name="agent-A")
    context_storage.set_trace_data(trace_a)
    tracer.after_agent_callback(_callback_context("invocation-A"))
    assert trace_a.output == {"text": "ALPHA"}  # A's output, never B's

    # Invocation B's agent finishes and still gets its own output.
    trace_b = TraceData(name="agent-B")
    context_storage.set_trace_data(trace_b)
    tracer.after_agent_callback(_callback_context("invocation-B"))
    assert trace_b.output == {"text": "BRAVO"}


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_agent_callback__no_cached_output__stamps_none():
    # No model output cached for this invocation (e.g. a failed conversion
    # cleared it): after_agent_callback must stamp None, never a stale value.
    tracer = OpikTracer(project_name="adk-test")

    trace = TraceData(name="agent")
    context_storage.set_trace_data(trace)
    tracer.after_agent_callback(_callback_context("invocation-without-output"))

    assert trace.output is None


def _model_response(text: str, *, partial: bool) -> LlmResponse:
    return LlmResponse(
        content=genai_types.Content(role="model", parts=[genai_types.Part(text=text)]),
        partial=partial,
    )


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model_callback__recovers_output_when_span_detached():
    # ContextCacheConfig (issue #5524) wraps the LLM call in its own OTel span,
    # which forks the async context under SSE streaming — so the span pushed in
    # before_model_callback is invisible here and top_span_data() is None. The
    # actual model output must still be recovered and land on the trace output.
    # Verified through the public callbacks only: after_model recovers, then
    # after_agent stamps the recovered answer onto the trace.
    tracer = OpikTracer(project_name="adk-test")
    assert context_storage.top_span_data() is None  # detached: no current span

    tracer.after_model_callback(
        _callback_context("inv-detached"),
        _model_response("RECOVERED ANSWER", partial=False),
    )

    trace = TraceData(name="agent")
    context_storage.set_trace_data(trace)
    tracer.after_agent_callback(_callback_context("inv-detached"))

    assert "RECOVERED ANSWER" in str(trace.output)


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model_callback__detached_span__partial_chunk_discards():
    # Partial streaming chunks must not cache — and they clear any prior value for
    # the invocation (discard up front), so a stale earlier output can't leak onto
    # the trace before the final response arrives. Driven entirely through the
    # public callbacks: a recovered final response, then a partial chunk for the
    # same invocation, must leave the trace output empty.
    tracer = OpikTracer(project_name="adk-test")
    assert context_storage.top_span_data() is None

    # An earlier final response was recovered for this invocation...
    tracer.after_model_callback(
        _callback_context("inv-partial"),
        _model_response("EARLIER ANSWER", partial=False),
    )
    # ...then a partial chunk arrives (still detached): it must discard, not stamp.
    tracer.after_model_callback(
        _callback_context("inv-partial"), _model_response("strea", partial=True)
    )

    trace = TraceData(name="agent")
    context_storage.set_trace_data(trace)
    tracer.after_agent_callback(_callback_context("inv-partial"))

    assert trace.output is None  # no stale "EARLIER ANSWER" leaked through
