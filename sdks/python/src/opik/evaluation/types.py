import enum
from typing import Any, Callable, Dict, List, Union

from . import test_result
from .metrics import score_result

LLMTask = Callable[[Dict[str, Any]], Dict[str, Any]]

ScoringKeyMappingType = Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]

ExperimentScoreFunction = Callable[
    [List[test_result.TestResult]],
    Union[score_result.ScoreResult, List[score_result.ScoreResult]],
]


class ErrorTolerance(enum.IntEnum):
    """Which classes of failure an evaluation tolerates before it aborts.

    Higher values tolerate more. Only the members defined here — or their exact int
    values, since this is an ``IntEnum`` — are accepted; anything else raises
    ``ValueError``. Values are spaced by ten so levels can be inserted above, below
    or between the existing ones without renumbering.
    """

    METRIC_ERRORS = 10
    """Default. Errors raised while a metric computes its score are recorded as
    failed score results and the run continues. Of the failures that happen before
    ``score`` is entered, a missing required score argument and an item-level
    evaluator that cannot be built abort the run; an evaluator whose type this SDK
    does not support is always reported as a failed score instead."""

    ALL_SCORING_ERRORS = 20
    """Also tolerate errors that prevent a metric from being scored at all — a
    required score argument the dataset does not provide, or an item-level
    evaluator that cannot be built. A failure of the evaluation task itself
    still aborts."""
