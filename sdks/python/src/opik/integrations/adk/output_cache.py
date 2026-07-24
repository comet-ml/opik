from typing import Any, Dict, Optional

from .bounded_cache import DEFAULT_MAX_SIZE, BoundedCache

# A single OpikTracer instance is shared across all concurrent ADK invocations
# (the documented ``track_adk_agent_recursive`` pattern), so the most recent
# model output is cached per ``invocation_id`` to stop concurrent invocations
# from overwriting each other's output.
#
# The cache is size-bounded (see ``BoundedCache``) on purpose: ADK has no
# guaranteed invocation-teardown hook — ``after_agent_callback`` is skipped on
# agent errors, early escalation/``end_invocation``, and stream cancellation —
# so relying on a callback to free entries would let the map grow without bound
# on a long-lived shared tracer. Eviction caps memory regardless.


class LastModelOutputCache:
    """Bounded cache of the last model output per invocation_id.

    ADK delivers a model response in ``after_model_callback`` but the agent's
    output is stamped later in ``after_agent_callback``; this carries the value
    between them, keyed by ``invocation_id`` and isolated across concurrent
    invocations that share one tracer.
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._cache: BoundedCache[str, Dict[str, Any]] = BoundedCache(max_size)

    def set(self, invocation_id: str, output: Dict[str, Any]) -> None:
        self._cache.set(invocation_id, output)

    def get(self, invocation_id: str) -> Optional[Dict[str, Any]]:
        return self._cache.get(invocation_id)

    def discard(self, invocation_id: str) -> None:
        """Drop the cached output for ``invocation_id`` if present.

        Used when a model call produces no usable output so a stale value from
        an earlier call in the same invocation isn't later stamped onto a span
        or trace.
        """
        self._cache.pop(invocation_id)
