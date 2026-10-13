"""Base types and protocols for chunk aggregators."""

import logging
from typing import Any, Dict, List, Protocol

LOGGER = logging.getLogger(__name__)


def updated_token_count(value: Any, current: int) -> int:
    """`value` when it is a real count, otherwise the running `current` count.

    A later chunk can report a token field as null, and booleans are ints in
    Python. Assigning either through would discard a count an earlier chunk
    already reported, and the `*_to_bedrock_usage` conversion would then raise
    `TypeError` on `None + int`. The stream wrapper calls the aggregator in its
    `finally`, where a raise skips `finally_callback` and leaves the span open,
    so every aggregator reads its counts through this helper.

    That is also why an unexpected type is not raised on: the same `finally`
    would skip `finally_callback` and the span would stay open, which is the
    failure this helper exists to prevent. A type that is neither null, boolean
    nor int is a provider anomaly rather than a recoverable one, so it is logged
    instead of passed on silently.
    """
    if isinstance(value, bool) or not isinstance(value, int):
        if value is not None and not isinstance(value, bool):
            LOGGER.warning(
                "Ignoring a token count of type %s (%r), keeping the running count %d",
                type(value).__name__,
                value,
                current,
            )
        return current
    return value


class ChunkAggregator(Protocol):
    """Protocol for chunk aggregators."""

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """
        Aggregate streaming chunks in the format native to the provider.

        The returned dictionary contains:
        - Provider-specific response structure (e.g., Claude's content blocks, Nova's output)
        - "usage" field with Bedrock format: {inputTokens, outputTokens, totalTokens}

        Args:
            items: List of chunk items from the event stream

        Returns:
            Dict with provider-native structure and Bedrock-standardized usage
        """
        ...
