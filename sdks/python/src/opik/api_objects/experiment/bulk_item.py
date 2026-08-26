import dataclasses
import datetime
from typing import Any, Dict, List, Optional

from opik.types import ErrorInfoDict, FeedbackScoreDict, SpanType

JsonLike = Dict[str, Any]


@dataclasses.dataclass
class ExperimentItemBulkTrace:
    """A trace to create alongside an experiment item in a bulk upload."""

    start_time: datetime.datetime
    id: Optional[str] = None
    name: Optional[str] = None
    project_name: Optional[str] = None
    end_time: Optional[datetime.datetime] = None
    input: Optional[JsonLike] = None
    output: Optional[JsonLike] = None
    metadata: Optional[JsonLike] = None
    tags: Optional[List[str]] = None
    error_info: Optional[ErrorInfoDict] = None
    thread_id: Optional[str] = None


@dataclasses.dataclass
class ExperimentItemBulkSpan:
    """A span to create alongside an experiment item in a bulk upload."""

    start_time: datetime.datetime
    id: Optional[str] = None
    parent_span_id: Optional[str] = None
    name: Optional[str] = None
    type: Optional[SpanType] = None
    end_time: Optional[datetime.datetime] = None
    input: Optional[JsonLike] = None
    output: Optional[JsonLike] = None
    metadata: Optional[JsonLike] = None
    model: Optional[str] = None
    provider: Optional[str] = None
    tags: Optional[List[str]] = None
    usage: Optional[Dict[str, int]] = None
    error_info: Optional[ErrorInfoDict] = None
    total_estimated_cost: Optional[float] = None


@dataclasses.dataclass
class ExperimentItemBulkRecord:
    """
    A single experiment item to upload via :meth:`Experiment.batch_upload_items`.

    Provide either ``evaluate_task_result`` (the backend creates the trace) or
    ``trace`` (you supply it), but never both.
    """

    dataset_item_id: str
    evaluate_task_result: Optional[JsonLike] = None
    trace: Optional[ExperimentItemBulkTrace] = None
    spans: Optional[List[ExperimentItemBulkSpan]] = None
    feedback_scores: Optional[List[FeedbackScoreDict]] = None
