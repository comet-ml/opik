"""Helper utilities for Hierarchical Reflective Optimizer.

Contains utility functions for hierarchical root cause analysis,
failure mode processing, and other utility functions.
"""


def calculate_improvement(current_score: float, previous_score: float) -> float:
    """
    Calculate the improvement percentage between scores.

    Args:
        current_score: The current score
        previous_score: The previous score to compare against

    Returns:
        Improvement percentage (0 if previous_score <= 0)
    """
    return (
        (current_score - previous_score) / previous_score if previous_score > 0 else 0
    )
