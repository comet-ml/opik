"""Validators for dataset and evaluation suite operations."""

from typing import Any, List


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
