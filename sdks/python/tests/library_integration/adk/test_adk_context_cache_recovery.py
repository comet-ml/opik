"""Regression tests for comet-ml/opik#5524: recover per-LLM spans when ADK's
ContextCacheConfig detaches the async context under SSE streaming.

``before_model_callback`` creates the Opik LLM span and ``after_model_callback``
finalizes it, normally handing it over via the contextvar span stack. When ADK's
``ContextCacheConfig`` forks the async context (SSE streaming on Cloud Run), that
stack no longer holds the span in ``after_model_callback`` — so the span was
never finalized (stuck ``started``, empty output). The tracer now also registers
the span under a stable, per-model-call key (``id(callback_context.actions)``)
and recovers it from that registry, falling back to the context stack top when
it is still our LLM span.

The shared bounded cache is unit-tested directly; recovery is exercised against
the modern ``OpikTracer`` by driving the real before/after callbacks and reading
the span from the public context storage (the modern tracer is used on
ADK >= 1.3.0, so these are skipped for older ADK). The autouse
``clear_context_storage`` fixture (tests/conftest.py) resets context storage.
"""

import threading
import types

from google.adk import models
from google.genai import types as genai_types

from opik import context_storage
from opik.api_objects.trace.trace_data import TraceData
from opik.integrations.adk import OpikTracer
from opik.integrations.adk.bounded_cache import BoundedCache

from . import helpers


def _callback_context(invocation_id: str) -> types.SimpleNamespace:
    # A fresh object per call mirrors ADK giving each model call its own
    # EventActions instance; before/after_model_callback read only .actions (the
    # per-call key) and .invocation_id off the callback context.
    return types.SimpleNamespace(invocation_id=invocation_id, actions=object())


def _final_text_response(text: str) -> models.LlmResponse:
    return models.LlmResponse(
        content=genai_types.Content(role="model", parts=[genai_types.Part(text=text)]),
        partial=False,
    )


def _start_model_call(tracer: OpikTracer, ctx: object):
    # Run before_model_callback and return the LLM span it created, read from the
    # public context stack rather than the tracer's internals.
    tracer.before_model_callback(ctx, models.LlmRequest(model="gemini-2.0-flash"))
    return context_storage.top_span_data()


# --- shared bounded cache ---------------------------------------------------


def test_bounded_cache__isolates_by_key_and_pops():
    cache: BoundedCache[int, str] = BoundedCache()
    a, b = object(), object()

    cache.set(id(a), "A")
    cache.set(id(b), "B")  # would collide under a LIFO fallback

    assert cache.get(id(a)) == "A"
    assert cache.get(id(b)) == "B"
    assert cache.pop(id(a)) == "A"
    assert cache.get(id(a)) is None
    assert cache.pop(id(a)) is None  # idempotent


def test_bounded_cache__evicts_oldest_when_full():
    cache: BoundedCache[int, int] = BoundedCache(max_size=2)
    keys = [object() for _ in range(3)]
    for i, key in enumerate(keys):
        cache.set(id(key), i)

    assert cache.get(id(keys[0])) is None  # oldest evicted
    assert cache.get(id(keys[1])) == 1
    assert cache.get(id(keys[2])) == 2


def test_bounded_cache__thread_safe_under_concurrency():
    cache: BoundedCache[int, int] = BoundedCache(max_size=50)
    errors = []

    def worker(worker_id: int) -> None:
        try:
            for i in range(2000):
                key = object()
                cache.set(id(key), i)
                cache.get(id(key))
                cache.pop(id(key))
        except Exception as exc:  # pragma: no cover - only on a real failure
            errors.append(repr(exc))

    threads = [threading.Thread(target=worker, args=(t,)) for t in range(8)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert not errors


# --- recovery (modern tracer) ----------------------------------------------


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__detached_context__finalizes_recovered_llm_span():
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    span = _start_model_call(tracer, ctx)

    # Simulate the ContextCacheConfig detach: the LLM span is no longer on the
    # context span stack when after_model_callback runs.
    context_storage.pop_span_data()
    assert context_storage.top_span_data() is None

    tracer.after_model_callback(ctx, _final_text_response("hello"))

    # The span is finalized (output + end time) despite the detached stack.
    assert span.output is not None
    assert span.end_time is not None


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__normal_path__finalizes_and_pops_stack():
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    span = _start_model_call(tracer, ctx)
    assert context_storage.top_span_data() is span  # on the stack

    tracer.after_model_callback(ctx, _final_text_response("hello"))

    assert span.output is not None
    assert span.end_time is not None
    assert context_storage.top_span_data() is None  # popped off the stack


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__no_actions_key__finalizes_via_stack_fallback():
    # A callback context without ``actions`` (older ADK / custom callbacks) can't
    # be registered, so nothing is keyed — the same situation as a registry
    # eviction. after_model must fall back to the LLM span still on the stack
    # rather than dropping it.
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = types.SimpleNamespace(invocation_id="inv-1")  # no .actions

    span = _start_model_call(tracer, ctx)
    tracer.after_model_callback(ctx, _final_text_response("hello"))

    assert span.output is not None
    assert span.end_time is not None


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__parent_span_on_stack__finalizes_own_span_not_parent():
    # If the detach leaves a *parent* span on top of the stack, the tracer must
    # finalize its own recovered LLM span and leave the parent alone.
    trace = TraceData(name="agent")
    context_storage.set_trace_data(trace)
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    span = _start_model_call(tracer, ctx)

    # Detach our LLM span and leave a parent span on top instead.
    context_storage.pop_span_data()
    parent = trace.create_child_span_data(name="parent-agent")
    context_storage.add_span_data(parent)

    tracer.after_model_callback(ctx, _final_text_response("hello"))

    assert span.output is not None  # our span finalized (recovered by key)
    assert context_storage.top_span_data() is parent  # parent left untouched
