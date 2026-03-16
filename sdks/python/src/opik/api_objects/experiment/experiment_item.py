import dataclasses

from typing import Dict, Any, List, Optional
from opik.types import FeedbackScoreDict
from opik.rest_api.types import experiment_item_compare

AssertionResultDict = Dict[str, Any]


@dataclasses.dataclass
class ExperimentItemReferences:
    dataset_item_id: str
    trace_id: str
    project_name: Optional[str] = None


@dataclasses.dataclass
class ExperimentItemContent:
    id: str
    dataset_item_id: str
    trace_id: str
    dataset_item_data: Optional[Dict[str, Any]]
    evaluation_task_output: Optional[Dict[str, Any]]
    feedback_scores: List[FeedbackScoreDict]
    assertion_results: List[AssertionResultDict] = dataclasses.field(
        default_factory=list
    )

    @classmethod
    def from_rest_experiment_item_compare(
        cls,
        value: experiment_item_compare.ExperimentItemCompare,
        dataset_item_data: Optional[Dict[str, Any]] = None,
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

        raw_assertions = _extract_extra_field(value, "assertion_results")
        assertion_results: List[AssertionResultDict] = (
            raw_assertions if raw_assertions else []
        )

        return ExperimentItemContent(
            id=value.id,
            trace_id=value.trace_id,
            dataset_item_id=value.dataset_item_id,
            dataset_item_data=dataset_item_data if dataset_item_data else value.input,
            evaluation_task_output=value.output,
            feedback_scores=feedback_scores,
            assertion_results=assertion_results,
        )


def _extract_extra_field(model: Any, field_name: str) -> Any:
    if hasattr(model, "model_extra") and model.model_extra:
        return model.model_extra.get(field_name)
    return getattr(model, field_name, None)
