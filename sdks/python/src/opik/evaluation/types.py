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
    """How many failures an evaluation absorbs before it gives up.

    Higher values tolerate more. Plain ints work too, since this is an ``IntEnum``.
    Values are spaced by ten so levels can be inserted above, below or between the
    existing ones without renumbering.
    """

    METRIC_ERRORS = 10
    """Default. Errors raised *inside* ``score`` are recorded as failed score
    results and the run continues; anything else aborts."""

    ALL_SCORING_ERRORS = 20
    """Also tolerate errors that prevent a metric from being scored at all — a
    required score argument the dataset does not provide, or an item-level
    evaluator that cannot be built. A failure of the evaluation task itself
    still aborts."""
