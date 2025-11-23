"""
Result formatting operations for the Meta-Prompt Optimizer.

This module contains functions for calculating improvements and creating result objects.
"""

from typing import Any, Callable
import copy

from ....api_objects import chat_prompt
from ....api_objects.optimization_result import OptimizationResult, OptimizationRound


def calculate_improvement(current_score: float, previous_score: float) -> float:
    """
    Calculate the improvement percentage between scores.

    Args:
        current_score: Current score
        previous_score: Previous score

    Returns:
        Improvement percentage
    """
    return (
        (current_score - previous_score) / previous_score
        if previous_score > 0
        else 0
    )


