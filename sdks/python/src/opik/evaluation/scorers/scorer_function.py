import inspect
from typing import Any, Dict, Optional, Protocol, Union, List

from opik.evaluation.metrics import score_result
from opik.message_processing.emulation import models


class ScorerFunctionProtocol(Protocol):
    """
    Represents a protocol defining the structure for a scorer function.

    This protocol serves as a contract for implementing scorer functions used in
    evaluating tasks. A scorer function adhering to this protocol should take
    scoring inputs, task outputs, and optionally a task span model as input
    parameters and return a scoring result.
    """

    def __call__(
        self,
        scoring_inputs: Dict[str, Any],
        task_outputs: Dict[str, Any],
        task_span: Optional[models.SpanModel] = None,
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]: ...


ScorerFunction = ScorerFunctionProtocol


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


def has_task_span_in_parameters(scorer_function: ScorerFunction) -> bool:
    return "task_span" in inspect.signature(scorer_function).parameters
