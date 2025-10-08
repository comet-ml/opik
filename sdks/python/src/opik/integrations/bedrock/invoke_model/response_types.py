"""Response types for Bedrock invoke_model operations."""

from dataclasses import dataclass
from typing import Any, Dict


@dataclass
class BedrockAggregatedResponse:
    """
    Response from invoke_model_with_response_stream after chunk aggregation.

    Contains the aggregated response in the provider's native format
    with standardized Bedrock usage format.
    """

    # Provider-native response structure
    native_response: Dict[str, Any]

    # Standardized Bedrock usage format
    usage: Dict[str, Any]  # {inputTokens, outputTokens, totalTokens}

    # Response metadata from Bedrock
    response_metadata: Dict[str, Any]

    def to_output_format(self) -> Dict[str, Any]:
        """Convert to output format for span logging."""
        return {"body": self.native_response}

    def to_metadata_format(self) -> Dict[str, Any]:
        """Convert to metadata format for span logging."""
        return {
            "created_from": "bedrock",
            **self.response_metadata,
        }
