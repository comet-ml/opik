import collections
import threading
from typing import Callable, Generic, List, Optional, Tuple, TypeVar

_K = TypeVar("_K")
_V = TypeVar("_V")

DEFAULT_MAX_SIZE = 1000


class BoundedCache(Generic[_K, _V]):
    """A thread-safe, size-bounded mapping that evicts the oldest entry once it
    grows past ``max_size``.

    Shared by the ADK tracer's runtime caches (the per-invocation model-output
    cache and the pending-LLM-span registry), which are held on a long-lived,
    concurrently-used tracer and so must stay bounded and locked in one place.

    An optional ``on_evict`` callback is invoked with each ``(key, value)``
    dropped by a capacity eviction -- never for an overwrite of an existing key
    or an explicit ``pop``. It runs *outside* the internal lock so it may do
    slower work (e.g. flushing a stranded span) without blocking other callers
    and without any risk of re-entrant deadlock; it must not raise.
    """

    def __init__(
        self,
        max_size: int = DEFAULT_MAX_SIZE,
        on_evict: Optional[Callable[[_K, _V], None]] = None,
    ) -> None:
        self._lock = threading.Lock()
        self._entries: "collections.OrderedDict[_K, _V]" = collections.OrderedDict()
        self._max_size = max_size
        self._on_evict = on_evict

    def set(self, key: _K, value: _V) -> None:
        evicted: List[Tuple[_K, _V]] = []
        with self._lock:
            self._entries[key] = value
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_size:
                # Oldest first; the just-inserted key is newest, so it survives
                # whenever max_size >= 1.
                evicted.append(self._entries.popitem(last=False))
        if self._on_evict is not None:
            for evicted_key, evicted_value in evicted:
                self._on_evict(evicted_key, evicted_value)

    def get(self, key: _K) -> Optional[_V]:
        with self._lock:
            return self._entries.get(key)

    def pop(self, key: _K) -> Optional[_V]:
        with self._lock:
            return self._entries.pop(key, None)
