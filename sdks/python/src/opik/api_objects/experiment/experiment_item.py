import dataclasses

from typing import Dict, Any, List, Optional, Type, TypeVar
from opik import exceptions
from opik.types import FeedbackScoreDict
from opik.rest_api.types import experiment_item_compare

AssertionResultDict = Dict[str, Any]

T = TypeVar("T")


def require_json_type(value: Any, expected: Type[T], what: str) -> T:
    """``value`` when it has the shape the Compare schema declares, else raise.

    The read parses the endpoint's JSON itself, so nothing re-derives the schema the
    generated models used to enforce. Without this, a field the backend sent with the
    wrong shape either surfaces as an ``AttributeError`` from deep inside the parse or,
    worse, parses silently: ``list()`` over a dict yields its keys and over a string
    yields its characters, which would turn a malformed ``assertion_results`` into
    plausible-looking nonsense rather than an error.
    """
    if not isinstance(value, expected):
        raise exceptions.OpikException(
            f"The experiment Compare response is malformed: {what} is a "
            f"{type(value).__name__}, not a {expected.__name__}."
        )
    return value


def optional_json_list(value: Any, what: str) -> List[Any]:
    """``value`` as a list, an absent or null field reading as an empty one.

    Spelled out rather than ``value or []``, which defaults on every falsy value: ``""``,
    ``0`` and ``False`` are not lists, and a field carrying one of them means the
    response is malformed, not that the list is empty. Only an absent key and an
    explicit ``null`` are the empty list.
    """
    if value is None:
        return []
    return require_json_type(value, list, what)


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
        require_json_type(value, dict, "an `experiment_items` entry")
        feedback_scores: List[FeedbackScoreDict] = [
            {
                "category_name": score.get("category_name"),
                "name": score.get("name"),
                "reason": score.get("reason"),
                "value": score.get("value"),
            }
            for score in optional_json_list(
                value.get("feedback_scores"), "`feedback_scores`"
            )
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
            assertion_results=list(
                optional_json_list(
                    value.get("assertion_results"), "`assertion_results`"
                )
            ),
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
