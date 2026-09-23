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
    execution_policy: Optional[Dict[str, Any]] = None


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
    def from_compare_dict(
        cls,
        value: Dict[str, Any],
        dataset_item_data: Optional[Dict[str, Any]] = None,
    ) -> "ExperimentItemContent":
        """Build from the endpoint's own JSON, skipping the generated REST models.

        Parsing a page into those models re-derives type hints per node, which costs
        more than the request itself on a large experiment; the dataset read hands back
        plain dicts for the same reason.
        """
        feedback_scores: List[FeedbackScoreDict] = [
            {
                "category_name": score.get("category_name"),
                "name": score.get("name"),
                "reason": score.get("reason"),
                "value": score.get("value"),
            }
            for score in value.get("feedback_scores") or []
        ]

        return cls(
            # Indexed, not `.get`: a page missing these is a broken response, and a
            # record built around None would only fail further away.
            id=value["id"],
            trace_id=value["trace_id"],
            dataset_item_id=value["dataset_item_id"],
            dataset_item_data=dataset_item_data
            if dataset_item_data
            else value.get("input"),
            evaluation_task_output=value.get("output"),
            feedback_scores=feedback_scores,
            assertion_results=list(value.get("assertion_results") or []),
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

        if value.assertion_results is None:
            assertion_results: List[AssertionResultDict] = []
        else:
            assertion_results = [
                ar
                if isinstance(ar, dict)
                else {"value": ar.value, "passed": ar.passed, "reason": ar.reason}
                for ar in value.assertion_results
            ]

        return ExperimentItemContent(
            id=value.id,
            trace_id=value.trace_id,
            dataset_item_id=value.dataset_item_id,
            dataset_item_data=dataset_item_data if dataset_item_data else value.input,
            evaluation_task_output=value.output,
            feedback_scores=feedback_scores,
            assertion_results=assertion_results,
        )
