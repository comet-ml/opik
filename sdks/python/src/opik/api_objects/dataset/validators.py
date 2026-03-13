"""Validators for dataset and evaluation suite operations."""

from typing import Any, List, Optional


def validate_evaluators(evaluators: List[Any], context: str) -> None:
    """
    Validate that all evaluators are LLMJudge instances.

    Args:
        evaluators: List of evaluators to validate.
        context: Description of where the evaluators are being used (for error message).

    Raises:
        TypeError: If any evaluator is not an LLMJudge instance.
    """
    from opik.evaluation.suite_evaluators import llm_judge

    for evaluator in evaluators:
        if not isinstance(evaluator, llm_judge.LLMJudge):
            raise TypeError(
                f"Evaluation suites only support LLMJudge evaluators. "
                f"Got {type(evaluator).__name__} in {context}. "
                f"Use LLMJudge from opik.evaluation.suite_evaluators instead."
            )


def resolve_evaluators(
    assertions: Optional[List[str]],
    evaluators: Optional[List[Any]],
    context: str,
) -> Optional[List[Any]]:
    """
    Resolve assertions shorthand and/or evaluators into a list of LLMJudge instances.

    Args:
        assertions: List of assertion strings to build an LLMJudge from.
        evaluators: List of pre-built LLMJudge evaluators.
        context: Description of where this is used (for error messages).

    Returns:
        A list of LLMJudge instances, or None if neither was provided.

    Raises:
        ValueError: If both assertions and evaluators are provided.
        TypeError: If any evaluator is not an LLMJudge instance.
    """
    if assertions is not None and evaluators is not None:
        raise ValueError(
            f"Cannot specify both 'assertions' and 'evaluators' for {context}. "
            f"Use 'assertions' for a shorthand or 'evaluators' for full control, "
            f"but not both."
        )

    if assertions is not None:
        if not assertions:
            return []
        from opik.evaluation.suite_evaluators import llm_judge

        return [llm_judge.LLMJudge(assertions=assertions)]

    if evaluators is not None:
        validate_evaluators(evaluators, context)
        return evaluators

    return None


_VALID_ITEM_KEYS = {"data", "assertions", "description", "execution_policy"}
_VALID_EXECUTION_POLICY_KEYS = {"runs_per_item", "pass_threshold"}


def validate_execution_policy(ep: Any, context: str = "execution_policy") -> None:
    if not isinstance(ep, dict):
        raise TypeError(f"'{context}' must be a dict, got {type(ep).__name__}")
    unknown_keys = set(ep.keys()) - _VALID_EXECUTION_POLICY_KEYS
    if unknown_keys:
        raise ValueError(
            f"'{context}' has unknown keys: {unknown_keys}. "
            f"Valid keys are: {_VALID_EXECUTION_POLICY_KEYS}"
        )
    missing_keys = _VALID_EXECUTION_POLICY_KEYS - set(ep.keys())
    if missing_keys:
        raise ValueError(
            f"'{context}' is missing required keys: {missing_keys}. "
            f"Both 'runs_per_item' and 'pass_threshold' must be provided."
        )
    for key in ep:
        if not isinstance(ep[key], int):
            raise TypeError(
                f"'{context}.{key}' must be an int, got {type(ep[key]).__name__}"
            )


def validate_suite_items(items: List[Any]) -> None:
    for i, item in enumerate(items):
        if not isinstance(item, dict):
            raise TypeError(
                f"Item at index {i} must be a dict, got {type(item).__name__}"
            )

        unknown_keys = set(item.keys()) - _VALID_ITEM_KEYS
        if unknown_keys:
            raise ValueError(
                f"Item at index {i} has unknown keys: {unknown_keys}. "
                f"Valid keys are: {_VALID_ITEM_KEYS}"
            )

        if "data" not in item:
            raise ValueError(f"Item at index {i} is missing required key 'data'")
        if not isinstance(item["data"], dict):
            raise TypeError(
                f"Item at index {i} 'data' must be a dict, "
                f"got {type(item['data']).__name__}"
            )

        if "assertions" in item:
            assertions = item["assertions"]
            if not isinstance(assertions, list) or not all(
                isinstance(a, str) for a in assertions
            ):
                raise TypeError(
                    f"Item at index {i} 'assertions' must be a list of strings"
                )

        if "execution_policy" in item:
            validate_execution_policy(
                item["execution_policy"],
                context=f"Item at index {i} 'execution_policy'",
            )
