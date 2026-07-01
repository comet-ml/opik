import collections
import threading
from typing import Optional

from opik.api_objects.span.span_data import SpanData

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
# The registry is size-bounded (evict oldest) because ADK does not guarantee an
# ``after_model_callback`` for every ``before_model_callback`` (a before-callback
# may short-circuit by returning a response, or the model call may error), so
# unclaimed entries must not accumulate on a long-lived shared tracer.
_DEFAULT_MAX_SIZE = 1000


class PendingLlmSpanRegistry:
    """Thread-safe, size-bounded registry of in-flight LLM spans, keyed by a
    stable per-model-call id.
    """

    def __init__(self, max_size: int = _DEFAULT_MAX_SIZE) -> None:
        self._lock = threading.Lock()
        self._entries: "collections.OrderedDict[int, SpanData]" = (
            collections.OrderedDict()
        )
        self._max_size = max_size

    def register(self, key: int, span_data: SpanData) -> None:
        with self._lock:
            self._entries[key] = span_data
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_size:
                self._entries.popitem(last=False)

    def get(self, key: int) -> Optional[SpanData]:
        with self._lock:
            return self._entries.get(key)

    def pop(self, key: int) -> Optional[SpanData]:
        with self._lock:
            return self._entries.pop(key, None)
