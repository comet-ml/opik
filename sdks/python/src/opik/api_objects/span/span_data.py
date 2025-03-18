import logging
from typing import Any, Dict, Optional, Union

from opik import llm_usage
from opik.types import (
    LLMProvider,
    SpanType,
)

from .. import observation_data

LOGGER = logging.getLogger(__name__)


class SpanData(observation_data.ObservationData):
    """
    The SpanData object is returned when calling :func:`opik.opik_context.get_current_span_data` from a tracked function.
    """

    trace_id: str
    parent_span_id: Optional[str] = None
    type: SpanType = "general"
    usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None
    model: Optional[str] = None
    provider: Optional[Union[str, LLMProvider]] = None
    total_cost: Optional[float] = None

    def update(self, **new_data: Any) -> "SpanData":
        return super().update(**new_data)

    def init_end_time(self) -> "SpanData":
        return super().init_end_time()
