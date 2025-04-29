import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional, Union

from opik import Attachment, datetime_helpers, dict_utils, llm_usage
from opik.types import (
    ErrorInfoDict,
    FeedbackScoreDict,
    LLMProvider,
    SpanType,
)

from .. import helpers

LOGGER = logging.getLogger(__name__)


# Engineer note:
#
# After moving to minimal python version 3.10, a lot of common content
# from SpanData and TraceData can be moved to ObservationData parent dataclass.
# Before that it's impossible because of the dataclasses limitation to have optional arguments
# strictly after positional ones (including the attributes from the parent class).
# In python 3.10 @dataclass(kw_only=True) should help.
@dataclasses.dataclass
class SpanData:
    """
    The SpanData object is returned when calling :func:`opik.opik_context.get_current_span_data` from a tracked function.
    """

    trace_id: str
    id: str = dataclasses.field(default_factory=helpers.generate_id)
    parent_span_id: Optional[str] = None
    name: Optional[str] = None
    type: SpanType = "general"
    start_time: Optional[datetime.datetime] = dataclasses.field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    usage: Optional[Union[Dict[str, Any], llm_usage.OpikUsage]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
    project_name: Optional[str] = None
    model: Optional[str] = None
    provider: Optional[Union[str, LLMProvider]] = None
    error_info: Optional[ErrorInfoDict] = None
    total_cost: Optional[float] = None
    attachments: Optional[List[Attachment]] = None

    def update(self, **new_data: Any) -> "SpanData":
        for key, value in new_data.items():
            if value is None:
                continue

            if key not in self.__dict__:
                LOGGER.debug(
                    "An attempt to update span with parameter name it doesn't have: %s",
                    key,
                )
                continue

            if key == "metadata":
                self._update_metadata(value)
                continue
            elif key == "output":
                self._update_output(value)
                continue
            elif key == "input":
                self._update_input(value)
                continue
            elif key == "attachments":
                self._update_attachments(value)
                continue

            self.__dict__[key] = value

        return self

    def _update_metadata(self, new_metadata: Dict[str, Any]) -> None:
        if self.metadata is None:
            self.metadata = new_metadata
        else:
            self.metadata = dict_utils.deepmerge(self.metadata, new_metadata)

    def _update_output(self, new_output: Dict[str, Any]) -> None:
        if self.output is None:
            self.output = new_output
        else:
            self.output = dict_utils.deepmerge(self.output, new_output)

    def _update_input(self, new_input: Dict[str, Any]) -> None:
        if self.input is None:
            self.input = new_input
        else:
            self.input = dict_utils.deepmerge(self.input, new_input)

    def init_end_time(self) -> "SpanData":
        self.end_time = datetime_helpers.local_timestamp()

        return self

    def _update_attachments(self, attachments: List[Attachment]) -> None:
        if self.attachments is None:
            self.attachments = attachments
        else:
            self.attachments.extend(attachments)
