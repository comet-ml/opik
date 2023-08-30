import threading

from typing import Dict, TYPE_CHECKING, Optional

if TYPE_CHECKING:
    from .chain import Chain


class ChainThreadRegistry:
    def __init__(self) -> None:
        self._threads_chains: Dict[int, "Chain"] = {}
        self._lock = threading.Lock()

    def get(self) -> Optional["Chain"]:
        thread_id = threading.get_ident()
        with self._lock:
            if thread_id not in self._threads_chains:
                return None

            return self._threads_chains[thread_id]

    def add(self, new_chain: "Chain") -> None:
        thread_id = threading.get_ident()
        with self._lock:
            self._threads_chains[thread_id] = new_chain
