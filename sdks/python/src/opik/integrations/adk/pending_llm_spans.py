from typing import Any, Optional, Tuple

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
# registers the span against the ADK ``EventActions`` object that ADK builds once
# per model call and passes to both callbacks (invariant across streaming
# partials, immune to contextvar mutation), and ``after_model_callback`` recovers
# it by the same object.
#
# ``EventActions`` is not reliably hashable, so entries are keyed by
# ``id(actions)`` but ALSO hold a strong reference to the ``actions`` object and
# verify identity on lookup. That closes the ``id()``-recycling hazard: an entry
# that outlives its callback (a call whose ``after_model_callback`` never runs —
# a short-circuiting before-callback or a model error) keeps its ``actions``
# alive, so CPython can't reuse that id for a later call's ``EventActions``; and
# if an id ever did collide, the identity check refuses the stale span rather
# than finalizing it for the wrong call.
#
# It is size-bounded (see ``BoundedCache``) because those unclaimed entries must
# not accumulate on a long-lived shared tracer.


class PendingLlmSpanRegistry:
    """Bounded registry of in-flight LLM spans, keyed by the per-model-call
    ``EventActions`` object (by id, with an identity check).
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._cache: BoundedCache[int, Tuple[Any, SpanData]] = BoundedCache(max_size)

    def register(self, actions: Any, span_data: SpanData) -> None:
        self._cache.set(id(actions), (actions, span_data))

    def get(self, actions: Any) -> Optional[SpanData]:
        entry = self._cache.get(id(actions))
        if entry is not None and entry[0] is actions:
            return entry[1]
        return None

    def pop(self, actions: Any) -> Optional[SpanData]:
        entry = self._cache.pop(id(actions))
        if entry is not None and entry[0] is actions:
            return entry[1]
        return None
