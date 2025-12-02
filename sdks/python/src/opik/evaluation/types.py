from typing import Any, Callable, Dict, List, Union

from . import test_result
from .metrics import score_result

LLMTask = Callable[[Dict[str, Any]], Dict[str, Any]]

ScoringKeyMappingType = Dict[str, Union[str, Callable[[Dict[str, Any]], Any]]]

ExperimentScoreFunction = Callable[
    [List[test_result.TestResult]],
    Union[score_result.ScoreResult, List[score_result.ScoreResult]],
]
