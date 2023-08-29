import collections
import threading

from typing import Dict, Any, TYPE_CHECKING
from comet_llm import exceptions

if TYPE_CHECKING:
    from .chain import Chain


class ChainRegistry:
    def __init__(self,):
        self._threads_chains = {}
        self._lock = threading.Lock()

    def get(self, thread_id: int) -> "Chain":
        with self._lock:
            if thread_id not in self._threads_chains:
                return None

            return self._threads_chains[thread_id]

    def add(self, thread_id: int, new_chain: "Chain") -> None:
        with self._lock:
            self._threads_chains[thread_id] = new_chain
