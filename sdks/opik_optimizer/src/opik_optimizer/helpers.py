"""Helper functions for the Opik Optimizer SDK."""

from typing import Any


def drop_none(metadata: dict[str, Any]) -> dict[str, Any]:
    """
    Drop None values from a dictionary.

    Args:
        metadata: The dictionary to drop None values from

    Returns:
        A new dictionary with None values dropped
    """
    return {k: v for k, v in metadata.items() if v is not None}
