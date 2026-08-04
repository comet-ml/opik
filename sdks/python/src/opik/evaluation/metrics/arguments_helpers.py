import inspect
import logging
from typing import Any, Callable, Dict, List, Optional

import opik.exceptions as exceptions
from .. import types as evaluation_types

LOGGER = logging.getLogger(__name__)

MAX_REPORTED_AVAILABLE_KEYS = 20


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
            inspect.Parameter.POSITIONAL_ONLY,
            inspect.Parameter.POSITIONAL_OR_KEYWORD,
            inspect.Parameter.KEYWORD_ONLY,
        ):
            if name not in kwargs:
                missing_required_arguments.append(name)

    if len(missing_required_arguments) > 0:
        unused_mapping_arguments: List[str] = []
        if scoring_key_mapping:
            unused_mapping_arguments = list(
                set(key for key in scoring_key_mapping.values() if not callable(key))
                - set(kwargs.keys())
            )

        raise exceptions.ScoreMethodMissingArguments(
            score_name,
            missing_required_arguments,
            list(kwargs.keys()),
            unused_mapping_arguments,
        )


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
                mapped_inputs[key] = value(mapped_inputs)
            else:
                if value not in mapped_inputs:
                    # A mapping that matches nothing is always a mistake, and
                    # it only surfaces later if the metric happens to have no
                    # default for that argument. When it does have one, the
                    # metric silently scores the wrong thing, so this cannot
                    # stay at debug level (OPIK-6925). The available keys are
                    # the actionable part, but they are user data and can be
                    # numerous, so only a sample goes into the warning.
                    available_keys = list(mapped_inputs.keys())
                    sample = available_keys[:MAX_REPORTED_AVAILABLE_KEYS]
                    remaining = len(available_keys) - len(sample)
                    LOGGER.warning(
                        "Scoring key mapping value '%s' not found in dataset item. "
                        "Available keys (%d): %s%s",
                        value,
                        len(available_keys),
                        sample,
                        f" and {remaining} more" if remaining > 0 else "",
                    )
                else:
                    mapped_inputs[key] = mapped_inputs[value]

    return mapped_inputs
