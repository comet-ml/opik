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
    if assertions and evaluators:
        raise ValueError(
            f"Cannot specify both 'assertions' and 'evaluators' for {context}. "
            f"Use 'assertions' for a shorthand or 'evaluators' for full control, "
            f"but not both."
        )

    if assertions:
        from opik.evaluation.suite_evaluators import llm_judge

        return [llm_judge.LLMJudge(assertions=assertions)]

    if evaluators:
        validate_evaluators(evaluators, context)
        return evaluators

    return None
