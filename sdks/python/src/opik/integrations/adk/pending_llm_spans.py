from typing import Optional

from opik.api_objects.span.span_data import SpanData

from .bounded_cache import DEFAULT_MAX_SIZE, BoundedCache

# ADK creates the Opik LLM span in ``before_model_callback`` and finalizes it in
# ``after_model_callback``, normally handing it over through Opik's contextvar
# span stack (``OpikContextStorage``). When ADK's ``ContextCacheConfig`` is
# active, its ``handle_context_caching`` / ``create_cache`` OpenTelemetry spans
# add extra ``context.attach()/detach()`` cycles that, across an SSE streaming
# async-generator suspension, revert the contextvar to a snapshot predating that
# push — so ``top_span_data()`` returns ``None`` in ``after_model_callback`` and
# the span is never finalized (comet-ml/opik#5524).
#
# This registry is a contextvar-independent handoff: ``before_model_callback``
# registers the span under a key that is stable across the fork and unique per
# model call, and ``after_model_callback`` recovers it by the same key. The key
# is ``id(callback_context.actions)`` — ADK builds one ``EventActions`` per model
# call and passes the same instance to both callbacks (it is invariant across
# streaming partials), and object identity is immune to contextvar mutation.
#
# It is size-bounded (see ``BoundedCache``) because ADK does not guarantee an
# ``after_model_callback`` for every ``before_model_callback`` (a before-callback
# may short-circuit by returning a response, or the model call may error), so
# unclaimed entries must not accumulate on a long-lived shared tracer.


class PendingLlmSpanRegistry:
    """Bounded registry of in-flight LLM spans, keyed by a stable
    per-model-call id.
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._cache: BoundedCache[int, SpanData] = BoundedCache(max_size)

    def register(self, key: int, span_data: SpanData) -> None:
        self._cache.set(key, span_data)

    def get(self, key: int) -> Optional[SpanData]:
        return self._cache.get(key)

    def pop(self, key: int) -> Optional[SpanData]:
        return self._cache.pop(key)
