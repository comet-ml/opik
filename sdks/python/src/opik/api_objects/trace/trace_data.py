import logging
from typing import Any, Optional

from opik.types import CreatedByType

from .. import observation_data

LOGGER = logging.getLogger(__name__)


class TraceData(observation_data.ObservationData):
    """
    The TraceData object is returned when calling :func:`opik.opik_context.get_current_trace_data` from a tracked function.
    """

    thread_id: Optional[str] = None
    created_by: Optional[CreatedByType] = None

    def update(self, **new_data: Any) -> "TraceData":
        return super().update(**new_data)

    def init_end_time(self) -> "TraceData":
        return super().init_end_time()
