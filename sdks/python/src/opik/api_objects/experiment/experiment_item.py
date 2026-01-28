import dataclasses

from typing import Any
from opik.types import FeedbackScoreDict
from opik.rest_api.types import experiment_item_compare


@dataclasses.dataclass
class ExperimentItemReferences:
    dataset_item_id: str
    trace_id: str


@dataclasses.dataclass
class ExperimentItemContent:
    id: str
    dataset_item_id: str
    trace_id: str
    dataset_item_data: dict[str, Any] | None
    evaluation_task_output: dict[str, Any] | None
    feedback_scores: list[FeedbackScoreDict]

    @classmethod
    def from_rest_experiment_item_compare(
        cls,
        value: experiment_item_compare.ExperimentItemCompare,
        dataset_item_data: dict[str, Any] | None = None,
    ) -> "ExperimentItemContent":
        if value.feedback_scores is None:
            feedback_scores: list[FeedbackScoreDict] = []
        else:
            feedback_scores = [
                {
                    "category_name": rest_feedback_score.category_name,
                    "name": rest_feedback_score.name,
                    "reason": rest_feedback_score.reason,
                    "value": rest_feedback_score.value,
                }
                for rest_feedback_score in value.feedback_scores
            ]

        return ExperimentItemContent(
            id=value.id,
            trace_id=value.trace_id,
            dataset_item_id=value.dataset_item_id,
            dataset_item_data=dataset_item_data if dataset_item_data else value.input,
            evaluation_task_output=value.output,
            feedback_scores=feedback_scores,
        )
