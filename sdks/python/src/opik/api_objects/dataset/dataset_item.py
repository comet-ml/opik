from typing import Optional, Dict, Any, List
import pydantic
import json
import hashlib
from .. import constants, helpers


class EvaluatorItem(pydantic.BaseModel):
    """
    An evaluator configuration for a dataset item.
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    name: str
    """The name of the evaluator."""

    type: str
    """The type of evaluator (e.g., 'llm_judge', 'code_metric')."""

    config: Dict[str, Any]
    """The evaluator configuration."""


class ExecutionPolicyItem(pydantic.BaseModel):
    """
    Execution policy for a dataset item.
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    runs_per_item: Optional[int] = None
    """Number of times to run the task for this item."""

    pass_threshold: Optional[int] = None
    """Minimum number of runs that must pass for the item to pass."""


class DatasetItem(pydantic.BaseModel):
    """
    A DatasetItem object representing an item in a dataset.
    The format is flexible.
    """

    model_config = pydantic.ConfigDict(extra="allow", strict=False)

    id: pydantic.SkipValidation[str] = pydantic.Field(
        default_factory=helpers.generate_id
    )
    """The unique identifier for this dataset item."""

    trace_id: Optional[str] = None
    """The ID of the trace associated with this dataset item."""

    span_id: Optional[str] = None
    """The ID of the span associated with this dataset item."""

    source: str = constants.DATASET_SOURCE_SDK
    """The source of the dataset item. Defaults to DATASET_SOURCE_SDK."""

    evaluators: Optional[List[EvaluatorItem]] = None
    """List of evaluators configured for this dataset item."""

    execution_policy: Optional[ExecutionPolicyItem] = None
    """Execution policy for this dataset item."""

    def get_content(
        self,
        include_id: bool = False,
    ) -> Dict[str, Any]:
        """
        Get the data content of the dataset item (extra fields).

        Note: evaluators and execution_policy are not included in data content

        Args:
            include_id: Whether to include the item ID in the content.

        Returns:
            Dictionary containing the item's extra fields.
        """
        content = {**self.model_extra}
        if include_id:
            content["id"] = self.id

        return content

    def content_hash(self) -> str:
        content = self.get_content()

        if self.evaluators is not None:
            content["evaluators"] = [e.model_dump() for e in self.evaluators]

        if self.execution_policy is not None:
            content["execution_policy"] = self.execution_policy.model_dump()

        json_string = json.dumps(content, sort_keys=True)
        hash_object = hashlib.sha256(json_string.encode())

        return hash_object.hexdigest()
