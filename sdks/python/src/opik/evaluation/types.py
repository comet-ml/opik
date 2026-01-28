from typing import Any, Union
from collections.abc import Callable

from . import test_result
from .metrics import score_result

LLMTask = Callable[[dict[str, Any]], dict[str, Any]]

ScoringKeyMappingType = dict[str, Union[str, Callable[[dict[str, Any]], Any]]]

ExperimentScoreFunction = Callable[
    [list[test_result.TestResult]],
    Union[score_result.ScoreResult, list[score_result.ScoreResult]],
]
