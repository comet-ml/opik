from typing import Optional, Dict, Any
import pydantic
import json
import hashlib
from .. import constants, helpers


class DatasetItem(pydantic.BaseModel):
    """
    A DatasetItem object representing an item in a dataset.
    The format is flexible.
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    id: str = pydantic.Field(default_factory=helpers.generate_id)
    """The unique identifier for this dataset item."""

    trace_id: Optional[str] = None
    """The ID of the trace associated with this dataset item."""

    span_id: Optional[str] = None
    """The ID of the span associated with this dataset item."""

    source: str = constants.DATASET_SOURCE_SDK
    """The source of the dataset item. Defaults to DATASET_SOURCE_SDK."""

    def get_content(self) -> Dict[str, Any]:
        user_defined_content = {
            key: value
            for key, value in self.__dict__.items()
            if key not in self.model_fields
        }

        return user_defined_content

    def content_hash(self) -> str:
        content = self.get_content()
        # Convert the dictionary to a JSON string with sorted keys for consistency
        json_string = json.dumps(content, sort_keys=True)

        # Compute the SHA256 hash of the JSON string
        hash_object = hashlib.sha256(json_string.encode())

        # Return the hexadecimal representation of the hash
        return hash_object.hexdigest()
