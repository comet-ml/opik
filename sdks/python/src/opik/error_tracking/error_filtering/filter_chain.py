import logging
from typing import List

from ..types import Event, Hint
from . import event_filter

LOGGER = logging.getLogger(__name__)


class FilterChain:
    def __init__(self, filters: List[event_filter.EventFilter]) -> None:
        self._filters = filters

    def validate(self, event: Event, hint: Hint) -> bool:
        try:
            for filter in self._filters:
                if not filter.process_event(event, hint):
                    return False

            return True
        except Exception as e:
            LOGGER.debug("Failed chain filters: %r" % e)

            return False
