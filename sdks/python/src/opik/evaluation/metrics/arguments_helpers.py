import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Set

from opik import exceptions
from .. import types as evaluation_types

LOGGER = logging.getLogger(__name__)


def raise_if_score_arguments_are_missing(
    score_function: Callable,
    score_name: str,
    kwargs: Dict[str, Any],
    scoring_key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
) -> None:
    signature = inspect.signature(score_function)

    parameters = signature.parameters

    missing_required_arguments: List[str] = []

    for name, param in parameters.items():
        if name == "self":
            continue

        if param.default == inspect.Parameter.empty and param.kind in (
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
            inspect.Parameter.KEYWORD_ONLY,
        ):
            if name not in kwargs:
                missing_required_arguments.append(name)

    if len(missing_required_arguments) > 0:
        message = (
            f"The scoring method {score_name} is missing arguments: {missing_required_arguments}. "
            f"These keys were not present in either the dataset item or the dictionary returned by the evaluation task. "
            f"You can either update the dataset or evaluation task to return this key or use the `scoring_key_mapping` to map existing items to the expected arguments."
            f"The available keys found in the dataset item and evaluation task output are: {list(kwargs.keys())}."
        )

        unused_mapping_arguments: Set[str] = set()
        if scoring_key_mapping:
            unused_mapping_arguments = set(
                key for key in scoring_key_mapping.values() if not callable(key)
            )

        unused_mapping_arguments -= set(kwargs.keys())

        if len(unused_mapping_arguments) > 0:
            message += f" Some keys in `scoring_key_mapping` didn't match anything: {unused_mapping_arguments}"

        raise exceptions.ScoreMethodMissingArguments(message)


def create_scoring_inputs(
    dataset_item: Dict[str, Any],
    task_output: Dict[str, Any],
    scoring_key_mapping: Optional[evaluation_types.ScoringKeyMappingType],
) -> Dict[str, Any]:
    mapped_inputs = {**dataset_item, **task_output}

    if scoring_key_mapping is None:
        return mapped_inputs
    else:
        for key, value in scoring_key_mapping.items():
            if callable(value):
                mapped_inputs[key] = value(dataset_item)
            else:
                if value not in mapped_inputs:
                    LOGGER.debug(
                        f"Scoring key mapping value '{value}' not found in dataset item. "
                        f"Available keys: {list(mapped_inputs.keys())}"
                    )
                else:
                    mapped_inputs[key] = mapped_inputs[value]

    return mapped_inputs
