import inspect
from typing import Any, Callable, Dict, Awaitable

from opik.evaluation.metrics import score_result

ScorerFunction = Callable[[Dict[str, Any], Dict[str, Any]], score_result.ScoreResult]
AsyncScorerFunction = Callable[
    [Dict[str, Any], Dict[str, Any]], Awaitable[score_result.ScoreResult]
]


EXPECTED_SCORER_FUNCTION_PARAMETERS = ["scoring_inputs", "task_outputs"]


def validate_scorer_function(scorer_functions: ScorerFunction) -> None:
    if not callable(scorer_functions):
        raise ValueError(
            "scorer_function must be a callable function that takes two arguments: scoring_inputs and task_outputs."
        )

    parameters = inspect.signature(scorer_functions).parameters
    if len(parameters) < 2:
        raise ValueError(
            "scorer_function must take at least two arguments: scoring_inputs and task_outputs."
        )

    names = parameters.keys()
    for expected_name in EXPECTED_SCORER_FUNCTION_PARAMETERS:
        if expected_name not in names:
            raise ValueError(
                f"scorer_function must take at least two arguments: {EXPECTED_SCORER_FUNCTION_PARAMETERS} - "
                f"the {expected_name} not found in function parameters: {names}"
            )
