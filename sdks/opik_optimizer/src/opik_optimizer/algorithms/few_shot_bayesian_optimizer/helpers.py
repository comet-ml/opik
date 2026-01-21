"""Helper utilities for Few Shot Bayesian Optimizer.

Contains utility functions for few-shot example selection,
Bayesian optimization helpers, and other utility functions.
"""

from typing import Any
import random

from ...utils import rng as rng_utils

# Constants for column value stringification
MISSING_VALUE_SENTINEL = "<missing>"
MAX_COLUMN_VALUE_LENGTH = 120


# FIXME: Use a centralized RNG function with seed and sampler across all optimizers
def make_rng(seed: int, *parts: object) -> random.Random:
    """Create a deterministic RNG (wrapper for shared rng_utils)."""
    return rng_utils.make_rng(seed, *parts)


def stringify_column_value(
    value: Any,
    missing_value_sentinel: str = MISSING_VALUE_SENTINEL,
    max_length: int = MAX_COLUMN_VALUE_LENGTH,
) -> str | None:
    """
    Convert a dataset value to a string suitable for column grouping.

    Args:
        value: The value to convert
        missing_value_sentinel: String to use for None values
        max_length: Maximum length for string values

    Returns:
        String representation of the value, or None if it cannot be stringified
    """
    if value is None:
        return missing_value_sentinel
    if isinstance(value, (list, dict)):
        return None
    text = str(value)
    if len(text) > max_length:
        return None
    return text
