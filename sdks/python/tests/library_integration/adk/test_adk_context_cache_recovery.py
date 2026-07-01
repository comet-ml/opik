"""Regression tests for comet-ml/opik#5524: recover per-LLM spans when ADK's
ContextCacheConfig detaches the async context under SSE streaming.

``before_model_callback`` creates the Opik LLM span and ``after_model_callback``
finalizes it, normally handing it over via the contextvar span stack. When ADK's
``ContextCacheConfig`` forks the async context (SSE streaming on Cloud Run), that
stack no longer holds the span in ``after_model_callback`` — so the span was
never finalized (stuck ``started``, empty output). The tracer now also registers
the span under a stable, per-model-call key (``id(callback_context.actions)``)
and recovers it from that registry when the stack is detached.

The registry is unit-tested directly; recovery is exercised against the modern
``OpikTracer`` with the span stack manually detached (the modern tracer is used
on ADK >= 1.3.0, so these are skipped for older ADK). The autouse
``clear_context_storage`` fixture (tests/conftest.py) resets context storage.
"""

import types

from google.adk import models
from google.genai import types as genai_types

from opik import context_storage
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer
from opik.integrations.adk.pending_llm_spans import PendingLlmSpanRegistry

from . import helpers


class _FakeSpan:
    def __init__(self, id: str) -> None:
        self.id = id


def _callback_context(invocation_id: str) -> types.SimpleNamespace:
    # before/after_model_callback read only .actions (the stable per-call key)
    # and .invocation_id off the callback context. A fresh object per call
    # mirrors ADK giving each model call its own EventActions instance.
    return types.SimpleNamespace(invocation_id=invocation_id, actions=object())


def _final_text_response(text: str) -> models.LlmResponse:
    return models.LlmResponse(
        content=genai_types.Content(role="model", parts=[genai_types.Part(text=text)]),
        partial=False,
    )


# --- registry ---------------------------------------------------------------


def test_pending_llm_span_registry__isolates_by_key():
    registry = PendingLlmSpanRegistry()
    a, b = object(), object()
    span_a, span_b = _FakeSpan("A"), _FakeSpan("B")

    registry.register(id(a), span_a)
    registry.register(id(b), span_b)  # would collide under a LIFO fallback

    assert registry.get(id(a)) is span_a
    assert registry.get(id(b)) is span_b
    assert registry.pop(id(a)) is span_a
    assert registry.get(id(a)) is None
    assert registry.pop(id(a)) is None  # idempotent


def test_pending_llm_span_registry__evicts_oldest_when_full():
    registry = PendingLlmSpanRegistry(max_size=2)
    keys = [object() for _ in range(3)]
    for i, key in enumerate(keys):
        registry.register(id(key), _FakeSpan(str(i)))

    # A before-callback without a matching after-callback can't grow the map.
    assert registry.get(id(keys[0])) is None  # oldest evicted
    assert registry.get(id(keys[1])) is not None
    assert registry.get(id(keys[2])) is not None


# --- recovery (modern tracer) ----------------------------------------------


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__detached_context__recovers_and_finalizes_llm_span():
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    tracer.before_model_callback(ctx, models.LlmRequest(model="gemini-2.0-flash"))
    span = tracer._pending_llm_spans.get(id(ctx.actions))
    assert span is not None

    # Simulate the ContextCacheConfig detach: the LLM span is no longer on the
    # context span stack when after_model_callback runs.
    context_storage.pop_span_data()
    assert context_storage.top_span_data() is None

    tracer.after_model_callback(ctx, _final_text_response("hello"))

    # The span is finalized from the registry despite the detached stack.
    assert span.output is not None
    assert span.end_time is not None
    assert tracer._pending_llm_spans.get(id(ctx.actions)) is None


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__normal_path__finalizes_and_clears_registry():
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    tracer.before_model_callback(ctx, models.LlmRequest(model="gemini-2.0-flash"))
    span = tracer._pending_llm_spans.get(id(ctx.actions))

    # Stack still holds the span (no detach).
    assert context_storage.top_span_data() is span
    tracer.after_model_callback(ctx, _final_text_response("hello"))

    assert span.output is not None
    assert span.end_time is not None
    # Finalized: removed from both the stack and the registry.
    assert context_storage.top_span_data() is None
    assert tracer._pending_llm_spans.get(id(ctx.actions)) is None


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__parent_span_on_stack__finalizes_own_span_not_parent():
    # If the detach leaves a *parent* span on top of the stack (not None), the
    # tracer must finalize its own recovered LLM span and leave the parent alone.
    trace = TraceData(name="agent")
    context_storage.set_trace_data(trace)
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    tracer.before_model_callback(ctx, models.LlmRequest(model="gemini-2.0-flash"))
    span = tracer._pending_llm_spans.get(id(ctx.actions))

    # Detach the LLM span and leave a parent span on top instead.
    context_storage.pop_span_data()
    parent = trace.create_child_span_data(name="parent-agent")
    context_storage.add_span_data(parent)

    tracer.after_model_callback(ctx, _final_text_response("hello"))

    assert span.output is not None  # our span finalized
    assert context_storage.top_span_data() is parent  # parent left untouched
    assert tracer._pending_llm_spans.get(id(ctx.actions)) is None
