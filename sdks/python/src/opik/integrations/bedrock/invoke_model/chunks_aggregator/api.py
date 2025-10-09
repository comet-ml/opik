"""
API functions for Bedrock chunk aggregation.

This module contains the main public functions for aggregating streaming chunks
from different Bedrock model formats into structured responses.
"""

from typing import Any, Dict, List

from . import format_detector
from .. import response_types


def aggregate_chunks_to_dataclass(
    items: List[Dict[str, Any]],
) -> response_types.BedrockAggregatedResponse:
    """
    Aggregate chunks directly to structured dataclass (used by stream wrapper).

    This function is called by the stream wrapper and returns a structured
    dataclass that the decorator can handle directly.

    Args:
        items: List of chunk items from the event stream

    Returns:
        BedrockAggregatedResponse with structured data
    """
    # Detect format and get appropriate aggregator
    format_name = format_detector.detect_format(items)
    aggregator = format_detector.get_aggregator(format_name)

    # Get aggregated data
    aggregated_data = aggregator.aggregate(items)

    # Extract components directly
    usage = aggregated_data.get("usage", {})
    native_response = {
        key: value for key, value in aggregated_data.items() if key != "usage"
    }

    # Create dataclass directly (response_metadata will be set by stream wrapper)
    return response_types.BedrockAggregatedResponse(
        native_response=native_response, usage=usage, response_metadata={}
    )
