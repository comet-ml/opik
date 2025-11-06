import dataclasses

from typing import Dict, Any, List, Optional
from opik.types import FeedbackScoreDict
from opik.rest_api.types import experiment_item_compare, dataset_item_compare


@dataclasses.dataclass
class ExperimentItemReferences:
    dataset_item_id: str
    trace_id: str


@dataclasses.dataclass
class ExperimentItemContent:
    id: str
    dataset_item_id: str
    trace_id: str
    dataset_item_data: Optional[Dict[str, Any]]
    evaluation_task_output: Optional[Dict[str, Any]]
    feedback_scores: List[FeedbackScoreDict]

    @classmethod
    def from_rest_experiment_item_compare(
        cls,
        value: experiment_item_compare.ExperimentItemCompare,
    ) -> "ExperimentItemContent":
        if value.feedback_scores is None:
            feedback_scores: List[FeedbackScoreDict] = []
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
            dataset_item_data=value.input,
            evaluation_task_output=value.output,
            feedback_scores=feedback_scores,
        )

    @classmethod
    def from_rest_experiment_item_compare_and_dataset_item_compare(
        cls,
        experiment_item: experiment_item_compare.ExperimentItemCompare,
        dataset_item: dataset_item_compare.DatasetItemCompare,
    ) -> "ExperimentItemContent":
        item_content = cls.from_rest_experiment_item_compare(experiment_item)

        item_content.dataset_item_data = dataset_item.data
        if item_content.dataset_item_data is not None:
            item_content.dataset_item_data.update({"id": dataset_item.id})

        return item_content
