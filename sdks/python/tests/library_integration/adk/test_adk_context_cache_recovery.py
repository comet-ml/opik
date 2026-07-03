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
from opik.integrations.adk.patchers.adk_otel_tracer import llm_span_helpers
from opik.integrations.adk.pending_llm_spans import PendingLlmSpanRegistry

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


def test_bounded_cache__distinct_keys__values_isolated_and_popped_independently():
    cache: BoundedCache[int, str] = BoundedCache()
    a, b = object(), object()

    cache.set(id(a), "A")
    cache.set(id(b), "B")  # would collide under a LIFO fallback

    assert cache.get(id(a)) == "A"
    assert cache.get(id(b)) == "B"
    assert cache.pop(id(a)) == "A"
    assert cache.get(id(a)) is None
    assert cache.pop(id(a)) is None  # idempotent


def test_bounded_cache__over_capacity__evicts_oldest_entry():
    cache: BoundedCache[int, int] = BoundedCache(max_size=2)
    keys = [object() for _ in range(3)]
    for i, key in enumerate(keys):
        cache.set(id(key), i)

    assert cache.get(id(keys[0])) is None  # oldest evicted
    assert cache.get(id(keys[1])) == 1
    assert cache.get(id(keys[2])) == 2


def test_bounded_cache__concurrent_set_get_pop__no_corruption():
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


# --- pending-span registry (identity check) ---------------------------------


def test_pending_llm_span_registry__distinct_actions__spans_isolated():
    registry = PendingLlmSpanRegistry()
    actions_a, actions_b = object(), object()
    span_a, span_b = object(), object()

    registry.register(actions_a, span_a)
    registry.register(actions_b, span_b)

    assert registry.get(actions_a) is span_a
    assert registry.get(actions_b) is span_b
    assert registry.pop(actions_a) is span_a
    assert registry.get(actions_a) is None


def test_pending_llm_span_registry__recycled_id__stale_span_not_returned():
    registry = PendingLlmSpanRegistry()
    stale_actions, live_actions = object(), object()
    stale_span = object()

    # Simulate CPython reusing an abandoned actions object's id for a later
    # call: the slot for live_actions' id holds an entry from a *different*
    # object. The identity check must refuse the stale span rather than
    # finalizing it for the wrong call.
    registry._cache.set(id(live_actions), (stale_actions, stale_span))

    assert registry.get(live_actions) is None
    assert registry.pop(live_actions) is None


def test_pending_llm_span_registry__eviction__finalizes_dropped_span_only():
    # The size bound can't tell an unclaimed pending span from a still-live one,
    # so an evicted entry is handed to on_evict_span (the tracer finalizes it)
    # instead of being silently dropped and stranded in the 'started' state. Only
    # the evicted (oldest) span is finalized; survivors stay recoverable.
    finalized = []
    registry = PendingLlmSpanRegistry(max_size=2, on_evict_span=finalized.append)
    actions = [object() for _ in range(3)]
    spans = [object() for _ in range(3)]
    for actions_obj, span_obj in zip(actions, spans):
        registry.register(actions_obj, span_obj)

    assert finalized == [spans[0]]  # oldest evicted + finalized, exactly once
    assert registry.get(actions[1]) is spans[1]  # survivors still recoverable
    assert registry.get(actions[2]) is spans[2]


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
    # be registered, so nothing is keyed — after_model must fall back to the LLM
    # span still on the stack rather than dropping it. (Registry eviction is a
    # distinct case: it finalizes the evicted span itself, tested separately.)
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


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_after_model__empty_final_response__does_not_leak_registry_entry():
    # A final empty-content response has nothing to finalize, but must still drop
    # the pending-span registry entry that before_model_callback registered (a
    # leak-regression check, so it observes the internal registry).
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    _start_model_call(tracer, ctx)
    # before_model_callback registered the span for this call...
    assert tracer._pending_llm_spans.get(ctx.actions) is not None

    empty_final = models.LlmResponse(
        content=genai_types.Content(role="model", parts=[genai_types.Part(text="")]),
        partial=False,
    )
    tracer.after_model_callback(ctx, empty_final)

    # ...and after_model_callback drops it, even with nothing to finalize (so
    # this fails on a broken registration as well as on a cleanup leak).
    assert tracer._pending_llm_spans.get(ctx.actions) is None


@helpers.pytest_skip_for_adk_older_than_1_3_0
def test_finalize_evicted_llm_span__started_span__closed_idempotently():
    # A span the registry evicts before after_model_callback claims it must be
    # finalized (end time + ready-for-finalization status) instead of being left
    # stuck in 'started', its TTFT bookkeeping dropped, and re-finalizing it must
    # be a harmless no-op — this is what before_model_callback wires the registry
    # to do on eviction.
    context_storage.set_trace_data(TraceData(name="agent"))
    tracer = OpikTracer(project_name="adk-test")
    ctx = _callback_context("inv-1")

    span = _start_model_call(tracer, ctx)
    assert span.end_time is None  # created 'started' by before_model_callback
    assert span.id in tracer._ttft_tracking

    tracer._finalize_evicted_llm_span(span)

    assert span.end_time is not None
    assert (
        span.metadata[llm_span_helpers.SPAN_STATUS]
        == llm_span_helpers.LLMSpanStatus.READY_FOR_FINALIZATION.value
    )
    assert span.metadata["opik_finalized_on_registry_eviction"] is True
    assert span.id not in tracer._ttft_tracking  # TTFT entry dropped, no leak

    # Idempotent: an already-finalized span (its after_model_callback won the
    # race) is left untouched and no exception escapes.
    first_end_time = span.end_time
    tracer._finalize_evicted_llm_span(span)
    assert span.end_time == first_end_time
