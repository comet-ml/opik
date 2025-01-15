import abc

from ..types import Event, Hint


class EventFilter(abc.ABC):
    @abc.abstractmethod
    def process_event(self, event: Event, hint: Hint) -> bool:
        pass
