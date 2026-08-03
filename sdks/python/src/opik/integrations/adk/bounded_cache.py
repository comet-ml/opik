import collections
import threading
from typing import Generic, Optional, TypeVar

_K = TypeVar("_K")
_V = TypeVar("_V")

DEFAULT_MAX_SIZE = 1000


class BoundedCache(Generic[_K, _V]):
    """A thread-safe, size-bounded mapping that evicts the oldest entry once it
    grows past ``max_size``.

    Shared by the ADK tracer's runtime caches (the per-invocation model-output
    cache and the pending-LLM-span registry), which are held on a long-lived,
    concurrently-used tracer and so must stay bounded and locked in one place.
    """

    def __init__(self, max_size: int = DEFAULT_MAX_SIZE) -> None:
        self._lock = threading.Lock()
        self._entries: "collections.OrderedDict[_K, _V]" = collections.OrderedDict()
        self._max_size = max_size

    def set(self, key: _K, value: _V) -> None:
        with self._lock:
            self._entries[key] = value
            self._entries.move_to_end(key)
            while len(self._entries) > self._max_size:
                self._entries.popitem(last=False)

    def get(self, key: _K) -> Optional[_V]:
        with self._lock:
            return self._entries.get(key)

    def pop(self, key: _K) -> Optional[_V]:
        with self._lock:
            return self._entries.pop(key, None)
