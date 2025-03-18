import datetime
import logging
from typing import Any, Dict, Optional, List

import pydantic

from opik import datetime_helpers, id_helpers, dict_utils
from opik.types import ErrorInfoDict, FeedbackScoreDict


LOGGER = logging.getLogger(__name__)

class ObservationData(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="allow", validate_default=False)

    id: pydantic.SkipValidation[str] = pydantic.Field(default_factory=id_helpers.generate_id)
    name: Optional[str] = None
    start_time: Optional[datetime.datetime] = pydantic.Field(
        default_factory=datetime_helpers.local_timestamp
    )
    end_time: Optional[datetime.datetime] = None
    metadata: Optional[Dict[str, Any]] = None
    input: Optional[Dict[str, Any]] = None
    output: Optional[Dict[str, Any]] = None
    tags: Optional[List[str]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
    project_name: Optional[str] = None
    error_info: Optional[ErrorInfoDict] = None

    def update(self, **new_data: Any) -> "ObservationData":
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

    def init_end_time(self) -> "ObservationData":
        self.end_time = datetime_helpers.local_timestamp()

        return self