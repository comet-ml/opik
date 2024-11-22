from typing import List, Callable, Dict, Any
import inspect
from opik import exceptions


def raise_if_score_arguments_are_missing(
    score_function: Callable, score_name: str, kwargs: Dict[str, Any]
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
        raise exceptions.ScoreMethodMissingArguments(
            f"The scoring method {score_name} is missing arguments: {missing_required_arguments}. "
            f"These keys were not present in either the dataset item or the dictionary returned by the evaluation task. "
            f"You can either update the dataset or evaluation task to return this key or use the `scoring_key_mapping` to map existing items to the expected arguments."
            f"The available keys found in the dataset item and evaluation task output are: {list(kwargs.keys())}."
        )
