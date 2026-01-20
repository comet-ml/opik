"""
Result formatting operations for the Meta-Prompt Optimizer.

This module contains functions for calculating improvements and creating result objects.
"""

from collections.abc import Sequence

from ....api_objects import chat_prompt
from ....core.state import AlgorithmResult
from ....core.results import OptimizationRound


def calculate_improvement(current_score: float, previous_score: float) -> float:
    """Calculate the improvement percentage between scores."""
    return (
        (current_score - previous_score) / previous_score if previous_score > 0 else 0
    )


def build_algorithm_result(
    *,
    best_prompts: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    best_score: float,
    history: Sequence[OptimizationRound],
    prompts_per_round: int,
    hall_of_fame_size: int,
    use_hall_of_fame: bool,
) -> AlgorithmResult:
    """Build the AlgorithmResult payload for MetaPromptOptimizer."""
    return AlgorithmResult(
        best_prompts=best_prompts
        if isinstance(best_prompts, dict)
        else {"prompt": best_prompts},
        best_score=best_score,
        history=list(history),
        metadata={
            "prompts_per_round": prompts_per_round,
            "hall_of_fame_size": hall_of_fame_size if use_hall_of_fame else 0,
        },
    )
