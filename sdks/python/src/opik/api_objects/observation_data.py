import dataclasses
import datetime
import logging
import time
from typing import Any, Dict, List, Optional, TypeVar

import opik.api_objects.attachment as attachment
import opik.datetime_helpers as datetime_helpers
from opik.types import ErrorInfoDict, FeedbackScoreDict
from . import data_helpers

LOGGER = logging.getLogger(__name__)

ObservationDataT = TypeVar("ObservationDataT", bound="ObservationData")


def _perf_counter_ns() -> int:
    """Return current performance counter in nanoseconds for high-precision TTFT timing."""
    return time.perf_counter_ns()


@dataclasses.dataclass(kw_only=True)
class ObservationData:
    """
    Base class for TraceData and SpanData containing common attributes and methods.

    This class uses Python 3.10's kw_only=True feature to allow optional parameters
    to be defined in the parent class while child classes can have required parameters.
    """

    name: Optional[str] = None
    start_time: Optional[datetime.datetime] = dataclasses.field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    _created_at_perf_counter_ns: int = dataclasses.field(
        default_factory=_perf_counter_ns
    )
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
    project_name: Optional[str] = None
    error_info: Optional[ErrorInfoDict] = None
    attachments: Optional[List[attachment.Attachment]] = None

    def update(self: ObservationDataT, **new_data: Any) -> ObservationDataT:
        """
        Updates the attributes of the object with the provided key-value pairs. This method checks if
        an attribute exists before updating it and merges the data appropriately for specific
        keywords like metadata, output, input, attachments, and tags. If a key doesn't correspond
        to an attribute of the object or the provided value is None, the update is skipped.

        Args:
            **new_data: Key-value pairs of attributes to update. Keys should match existing
                attributes on the object, and values that are None will not update.

        Returns:
            The updated object instance (preserves the actual subclass type).
        """
        for key, value in new_data.items():
            if value is None:
                continue

            if key not in self.__dict__ and key != "prompts":
                LOGGER.debug(
                    "An attempt to update observation with parameter name it doesn't have: %s",
                    key,
                )
                continue

            if key == "metadata":
                self.metadata = data_helpers.merge_metadata(
                    self.metadata, new_metadata=value
                )
                continue
            elif key == "output":
                self.output = data_helpers.merge_outputs(self.output, new_outputs=value)
                continue
            elif key == "input":
                self.input = data_helpers.merge_inputs(self.input, new_inputs=value)
                continue
            elif key == "attachments":
                self._update_attachments(value)
                continue
            elif key == "tags":
                self.tags = data_helpers.merge_tags(self.tags, new_tags=value)
                continue
            elif key == "prompts":
                self.metadata = data_helpers.merge_metadata(
                    self.metadata, new_metadata=new_data.get("metadata"), prompts=value
                )
                continue

            self.__dict__[key] = value

        return self

    def init_end_time(self: ObservationDataT) -> ObservationDataT:
        """Initialize the end_time to the current timestamp."""
        self.end_time = datetime_helpers.local_timestamp()
        return self

    def _update_attachments(self, attachments: List[attachment.Attachment]) -> None:
        """Merge new attachments with existing ones."""
        if self.attachments is None:
            self.attachments = attachments
        else:
            self.attachments.extend(attachments)
