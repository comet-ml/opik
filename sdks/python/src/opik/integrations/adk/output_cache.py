import collections
import threading
from typing import Any, Dict, Optional

# A single OpikTracer instance is shared across all concurrent ADK invocations
# (the documented ``track_adk_agent_recursive`` pattern), so the most recent
# model output is cached per ``invocation_id`` to stop concurrent invocations
# from overwriting each other's output.
#
# The cache is size-bounded (oldest entry evicted) on purpose: ADK has no
# guaranteed invocation-teardown hook — ``after_agent_callback`` is skipped on
# agent errors, early escalation/``end_invocation``, and stream cancellation —
# so relying on a callback to free entries would let the map grow without bound
# on a long-lived shared tracer. Eviction caps memory regardless. The bound is
# generous relative to the number of in-flight invocations (an entry is read by
# ``after_agent_callback`` microseconds after it is written), so it evicts only
# abandoned entries in practice.
_DEFAULT_MAX_SIZE = 1000


class LastModelOutputCache:
    """Thread-safe, size-bounded cache of the last model output per invocation_id.

    ADK delivers a model response in ``after_model_callback`` but the agent's
    output is stamped later in ``after_agent_callback``; this carries the value
    between them, keyed by ``invocation_id`` and isolated across concurrent
    invocations that share one tracer.
    """

    def __init__(self, max_size: int = _DEFAULT_MAX_SIZE) -> None:
        self._lock = threading.Lock()
        self._entries: "collections.OrderedDict[str, Dict[str, Any]]" = (
            collections.OrderedDict()
        )
        self._max_size = max_size

    def set(self, invocation_id: str, output: Dict[str, Any]) -> None:
        with self._lock:
            self._entries[invocation_id] = output
            self._entries.move_to_end(invocation_id)
            while len(self._entries) > self._max_size:
                self._entries.popitem(last=False)

    def get(self, invocation_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            return self._entries.get(invocation_id)

    def discard(self, invocation_id: str) -> None:
        """Drop the cached output for ``invocation_id`` if present.

        Used when a model call produces no usable output so a stale value from
        an earlier call in the same invocation isn't later stamped onto a span
        or trace.
        """
        with self._lock:
            self._entries.pop(invocation_id, None)
