from typing import Literal
from ..types import Event, Hint
from . import event_filter


class FilterByCount(event_filter.EventFilter):
    def __init__(self, max_count: int, level: Literal["error", "warning", "info"]):
        super().__init__()
        self._count = 0
        self._level = level
        self._max_count = max_count

    def process_event(self, event: Event, hint: Hint) -> bool:
        if not event.get("level", "") == self._level:
            return True

        if self._count >= self._max_count:
            return False

        self._count += 1
        return True
