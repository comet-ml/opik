import dataclasses
import datetime
import logging
from typing import Any, Dict, List, Optional

from opik import dict_utils, Attachment

from ... import datetime_helpers, id_helpers
from ...types import (
    CreatedByType,
    ErrorInfoDict,
    FeedbackScoreDict,
)

LOGGER = logging.getLogger(__name__)


# Engineer note:
#
# After moving to minimal python version 3.10, a lot of common content
# from SpanData and TraceData can be moved to ObservationData parent dataclass.
# Before that it's impossible because of the dataclasses limitation to have optional arguments
# strictly after positional ones (including the attributes from the parent class).
# In python 3.10 @dataclass(kw_only=True) should help.
@dataclasses.dataclass
class TraceData:
    """
    The TraceData object is returned when calling :func:`opik.opik_context.get_current_trace_data` from a tracked function.
    """

    id: str = dataclasses.field(default_factory=id_helpers.generate_id)
    name: Optional[str] = None
    start_time: Optional[datetime.datetime] = dataclasses.field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
    project_name: Optional[str] = None
    created_by: Optional[CreatedByType] = None
    error_info: Optional[ErrorInfoDict] = None
    thread_id: Optional[str] = None
    attachments: Optional[List[Attachment]] = None

    def update(self, **new_data: Any) -> "TraceData":
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

    def init_end_time(self) -> "TraceData":
        self.end_time = datetime_helpers.local_timestamp()
        return self

    def _update_attachments(self, attachments: List[Attachment]) -> None:
        if self.attachments is None:
            self.attachments = attachments
        else:
            self.attachments.extend(attachments)
