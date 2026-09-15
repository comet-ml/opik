import inspect
import logging
from typing import Any, Callable, Dict, List, Optional, Tuple

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


def select_score_arguments(
    score_function: Callable, kwargs: Dict[str, Any], score_name: str
) -> Tuple[List[Any], Dict[str, Any]]:
    """Split the scoring inputs into what the score signature can accept.

    Every dataset item key and task output key is offered to every metric, which
    breaks two kinds of signature:

    - A metric that does not declare ``**kwargs`` used to fail on every single
      item with an ``unexpected keyword argument`` TypeError — a message that
      reads like an SDK bug rather than a signature mismatch. Those keys are
      dropped now.
    - A positional-only parameter cannot be passed by keyword at all, so such a
      metric could never be scored. Those are returned separately, in signature
      order, to be passed positionally.

    Missing arguments are still reported: ``validate_score_arguments`` runs
    before this and only checks the parameters the metric declares, so filtering
    here cannot hide a key the metric actually asked for.
    """
    try:
        parameters = inspect.signature(score_function).parameters
    except (ValueError, TypeError):
        # Signature is not introspectable — pass everything, as before.
        return [], kwargs

    # Positional-only values are bound by position, so a gap cannot be skipped:
    # dropping an absent parameter would shift every later value one slot left and
    # score the metric against the wrong inputs. Only the contiguous leading run is
    # passed; anything supplied after a gap means the metric cannot be scored.
    positional_only_names = [
        name
        for name, parameter in parameters.items()
        if parameter.kind == inspect.Parameter.POSITIONAL_ONLY
    ]
    positional_arguments: List[Any] = []
    unbindable_names: List[str] = []
    for index, name in enumerate(positional_only_names):
        if name not in kwargs:
            unbindable_names = [
                later for later in positional_only_names[index:] if later not in kwargs
            ]
            if any(later in kwargs for later in positional_only_names[index + 1 :]):
                raise exceptions.ScoreMethodMissingArguments(
                    score_name,
                    unbindable_names,
                    list(kwargs.keys()),
                    None,
                )
            break
        positional_arguments.append(kwargs[name])

    accepts_any_keyword = any(
        parameter.kind == inspect.Parameter.VAR_KEYWORD
        for parameter in parameters.values()
    )

    keyword_arguments: Dict[str, Any] = {}
    for name, value in kwargs.items():
        parameter = parameters.get(name)
        if parameter is None:
            if accepts_any_keyword:
                keyword_arguments[name] = value
        elif parameter.kind != inspect.Parameter.POSITIONAL_ONLY:
            keyword_arguments[name] = value

    return positional_arguments, keyword_arguments


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
