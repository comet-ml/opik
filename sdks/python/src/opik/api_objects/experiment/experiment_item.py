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


def optional_json_list(
    value: Any, what: str, entry_type: Optional[type] = None
) -> List[Any]:
    """``value`` as a list, an absent or null field reading as an empty one.

    Spelled out rather than ``value or []``, which defaults on every falsy value: ``""``,
    ``0`` and ``False`` are not lists, and a field carrying one of them means the
    response is malformed, not that the list is empty. Only an absent key and an
    explicit ``null`` are the empty list.

    ``entry_type`` checks the entries too. A list of the right shape can still hold
    entries of the wrong one -- ``[null]`` is the shape to expect, since the backend
    omits a score rather than nulling it -- and the generated models rejected those as
    firmly as they rejected the outer list.
    """
    if value is None:
        return []
    entries = require_json_type(value, list, what)
    if entry_type is not None:
        for entry in entries:
            require_json_type(entry, entry_type, f"an entry of {what}")
    return entries


def _require_leaf_type(value: Any, expected: Any, what: str) -> None:
    """Check one leaf field of a Compare entry, when it is present and not null.

    Applied to every field this read copies into a declared-type SDK dict, and to no
    others -- that is the whole set, since the remaining fields on the Compare models
    are not read here at all. Passing a value of the wrong type through is not inert:
    ``passed`` is mapped onto a status with ``"passed" if passed else "failed"``, where
    the string ``"false"`` is truthy and records a failed assertion as passed; an
    assertion's ``value`` becomes the ``Required[str]`` ``name`` of an ingested
    assertion; and a score ``value`` is declared ``float`` and aggregated over.
    Re-deriving the *rest* of the schema per node is the cost this read exists to
    avoid, and this stops short of it.

    Absence stays permissive -- an omitted or ``null`` field is what the backend sends
    for a missing score, reason or verdict, and rejecting it would fail reads that work
    today. Only a present value of the wrong type is an error.

    ``bool`` is excluded from the numeric check: it is a subclass of ``int``, so
    ``"value": true`` would otherwise pass as a score of 1.
    """
    if value is None:
        return
    if expected is not bool and isinstance(value, bool):
        pass  # fall through to the failure below
    elif isinstance(value, expected):
        return
    names = (
        expected.__name__
        if isinstance(expected, type)
        else " or ".join(t.__name__ for t in expected)
    )
    raise exceptions.OpikException(
        f"The experiment Compare response is malformed: {what} is a "
        f"{type(value).__name__}, not {names}."
    )


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
        feedback_scores: List[FeedbackScoreDict] = []
        for score in optional_json_list(
            value.get("feedback_scores"), "`feedback_scores`", entry_type=dict
        ):
            _require_leaf_type(
                score.get("value"), (int, float), "a `feedback_scores` entry's `value`"
            )
            for key in ("name", "category_name", "reason"):
                _require_leaf_type(
                    score.get(key), str, f"a `feedback_scores` entry's `{key}`"
                )
            feedback_scores.append(
                {
                    "category_name": score.get("category_name"),
                    "name": score.get("name"),
                    "reason": score.get("reason"),
                    "value": score.get("value"),
                }
            )

        assertion_results: List[AssertionResultDict] = []
        for result in optional_json_list(
            value.get("assertion_results"), "`assertion_results`", entry_type=dict
        ):
            _require_leaf_type(
                result.get("passed"), bool, "an `assertion_results` entry's `passed`"
            )
            for key in ("value", "reason"):
                _require_leaf_type(
                    result.get(key), str, f"an `assertion_results` entry's `{key}`"
                )
            assertion_results.append(result)

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
            assertion_results=assertion_results,
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
