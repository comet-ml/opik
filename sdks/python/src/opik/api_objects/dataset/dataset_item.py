from typing import Optional, Dict, Any
import pydantic
from .. import constants


class DatasetItem(pydantic.BaseModel):
    """A DatasetItem object representing an item in a dataset."""

    model_config = pydantic.ConfigDict(strict=True)

    input: Dict[str, Any]
    """The input data for the dataset item."""

    expected_output: Optional[Dict[str, Any]] = None
    """The expected output for the dataset item."""

    metadata: Optional[Dict[str, Any]] = None
    """Additional metadata associated with the dataset item."""

    trace_id: Optional[str] = None
    """The ID of the trace associated with this dataset item."""

    span_id: Optional[str] = None
    """The ID of the span associated with this dataset item."""

    id: Optional[str] = None
    """The unique identifier for this dataset item."""

    source: str = constants.DATASET_SOURCE_SDK
    """The source of the dataset item. Defaults to DATASET_SOURCE_SDK."""

    def get_content(self) -> Dict[str, Any]:
        return {
            "input": self.input,
            "expected_output": self.expected_output,
            "metadata": self.metadata,
        }
