"""Base types and protocols for chunk aggregators."""

from typing import Any, Dict, List, Protocol


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
