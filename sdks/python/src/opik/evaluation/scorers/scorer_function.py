import inspect
from typing import Any, Callable, Dict

from opik.evaluation.metrics import score_result

ScorerFunction = Callable[[Dict[str, Any], Dict[str, Any]], score_result.ScoreResult]


EXPECTED_SCORER_FUNCTION_PARAMETERS = ["scoring_inputs", "task_outputs"]


def validate_scorer_function(scorer_function: ScorerFunction) -> None:
    if not callable(scorer_function):
        raise ValueError(
            f"scorer_function must be a callable function that takes two arguments: {EXPECTED_SCORER_FUNCTION_PARAMETERS}"
        )

    parameters = inspect.signature(scorer_function).parameters
    if len(parameters) < 2:
        raise ValueError(
            f"scorer_function must take at least two arguments: {EXPECTED_SCORER_FUNCTION_PARAMETERS}"
        )

    names = parameters.keys()
    for expected_name in EXPECTED_SCORER_FUNCTION_PARAMETERS:
        if expected_name not in names:
            raise ValueError(
                f"scorer_function must take at least two arguments: {EXPECTED_SCORER_FUNCTION_PARAMETERS} - "
                f"the {expected_name} is not found in function parameters: {names}"
            )
