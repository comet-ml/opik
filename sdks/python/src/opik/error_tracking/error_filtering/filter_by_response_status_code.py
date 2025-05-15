from typing import List, Optional
from ..types import Event, Hint
from . import event_filter


class FilterByResponseStatusCode(event_filter.EventFilter):
    def __init__(self, max_count: int, status_codes_to_drop: List[int]):
        super().__init__()

        self._status_codes_to_drop = status_codes_to_drop

    def process_event(self, event: Event, hint: Hint) -> bool:
        status_code = _try_get_status_code_from_error_tracking_extra(event)

        if status_code is None:
            status_code = _try_get_status_code_from_raised_exception(hint)

        if status_code in self._status_codes_to_drop:
            return False

        return True


def _try_get_status_code_from_error_tracking_extra(event: Event) -> Optional[int]:
    status_code_available = (
        "extra" in event
        and "error_tracking_extra" in event["extra"]
        and "status_code" in event["extra"]["error_tracking_extra"]
    )

    if status_code_available:
        return event["extra"]["error_tracking_extra"]["status_code"]

    return None


def _try_get_status_code_from_raised_exception(hint: Hint) -> Optional[int]:
    if "exc_info" in hint:
        exception_instance = hint["exc_info"][1]
        if hasattr(exception_instance, "status_code"):
            return getattr(exception_instance, "status_code")

    return None
