"""
Context building operations for the Meta-Prompt Optimizer.

This module contains functions for building task context and history context.
"""

from typing import Callable
import logging

import opik
from ....api_objects.optimization_result import OptimizationRound

logger = logging.getLogger(__name__)


def get_task_context(dataset: opik.Dataset | None, metric: Callable) -> str:
    """
    Get task-specific context from the dataset and metric configuration.
    Always sanitizes to prevent data leakage.

    Args:
        dataset: The dataset to extract context from
        metric: The evaluation metric

    Returns:
        Sanitized task context string
    """
    if dataset is None:
        return ""

    sample = None
    try:
        # Try get_items() first as it's the preferred method
        items = dataset.get_items()
        sample = items[0]  # Get first sample
    except Exception as e:
        logger.warning(f"Could not get sample from dataset: {e}")
        return ""

    if sample is None:
        return ""

    # Exclude output fields that would give away dataset structure
    excluded_keys = {
        "id",
        "answer",
        "label",
        "output",
        "expected_output",
        "ground_truth",
        "target",
        "metadata",
        "response",
    }

    # Only show INPUT fields with {var} delimiter syntax (Arize pattern)
    input_fields = [k for k in sample.keys() if k not in excluded_keys]

    context = "\nTask Context:\n"
    context += "Available input variables (use {variable_name} syntax): "
    context += ", ".join([f"{{{field}}}" for field in input_fields])
    context += "\n\n"

    # Generic metric description (NO specific names or formulas)
    context += (
        "Evaluation: Your output will be evaluated for accuracy and quality.\n"
    )
    context += (
        "Focus on producing clear, correct responses based on the input.\n\n"
    )

    # Sanitized example (inputs only, with variable syntax)
    sanitized_example = {k: v for k, v in sample.items() if k in input_fields}
    if sanitized_example:
        context += "Example input structure:\n"
        for key in input_fields[:2]:  # Show max 2 fields
            value = sample.get(key, "")
            # Truncate long values
            value_str = (
                str(value)[:100] + "..."
                if len(str(value)) > 100
                else str(value)
            )
            context += f"  {{{key}}}: {value_str}\n"

    return context


