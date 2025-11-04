import inspect
from typing import Any, Dict, Optional, Protocol, Union, List

from opik.evaluation.metrics import score_result
from opik.message_processing.emulation import models


class ScorerFunctionProtocol(Protocol):
    """
    Represents a protocol defining the structure for a scorer function.

    This protocol serves as a contract for implementing scorer functions used in
    evaluating tasks. A scorer function adhering to this protocol should take
    dataset item data, task outputs, and optionally a task span model as input
    parameters and return a scoring result.
    """

    def __call__(
        self,
        dataset_item: Dict[str, Any],
        task_outputs: Dict[str, Any],
        task_span: Optional[models.SpanModel] = None,
    ) -> Union[score_result.ScoreResult, List[score_result.ScoreResult]]: ...


ScorerFunction = ScorerFunctionProtocol


EXPECTED_SCORER_FUNCTION_PARAMETERS = ["dataset_item", "task_outputs"]


def validate_scorer_function(scorer_function: ScorerFunction) -> None:
    if not callable(scorer_function):
        raise ValueError("scorer_function must be a callable function")

    parameters = inspect.signature(scorer_function).parameters
    names = set(parameters.keys())

    # Check if it has both dataset_item and task_outputs
    has_dataset_item_and_task_outputs = all(
        param in names for param in EXPECTED_SCORER_FUNCTION_PARAMETERS
    )

    # Check if it has at least one task_span parameter
    has_task_span = "task_span" in names

    if not (has_dataset_item_and_task_outputs or has_task_span):
        raise ValueError(
            f"scorer_function must have either both 'dataset_item' and 'task_outputs' parameters "
            f"or at least one 'task_span' parameter. Found parameters: {list(names)}"
        )


def has_task_span_in_parameters(scorer_function: ScorerFunction) -> bool:
    return "task_span" in inspect.signature(scorer_function).parameters
