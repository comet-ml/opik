import collections

from typing import Dict


class LogsRegistry:
    def __init__(self) -> None:
        self._registry = collections.defaultdict(lambda: 0)

    def register_log(self, project_url: str) -> None:
        self._registry[project_url] += 1
    
    def as_dict(self) -> Dict[str, int]:
        return self._registry.copy()
    
    def empty(self) -> bool:
        return len(self._registry) == 0