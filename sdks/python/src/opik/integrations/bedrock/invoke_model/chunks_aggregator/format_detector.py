"""Format detection and aggregator registry."""

import json
from typing import Any, Callable, Dict, List

from .base import ChunkAggregator
from . import claude
from . import llama
from . import mistral
from . import nova


# Format detection functions
FormatDetector = Callable[[Dict[str, Any]], bool]


def _is_nova_format(chunk_data: Dict[str, Any]) -> bool:
    """Check if chunk is Nova format (camelCase fields)."""
    return "contentBlockDelta" in chunk_data or "messageStart" in chunk_data


def _is_claude_format(chunk_data: Dict[str, Any]) -> bool:
    """Check if chunk is Claude format (snake_case fields with type)."""
    return "type" in chunk_data


def _is_llama_format(chunk_data: Dict[str, Any]) -> bool:
    """Check if chunk is Llama format (generation field)."""
    return "generation" in chunk_data


def _is_mistral_format(chunk_data: Dict[str, Any]) -> bool:
    """Check if chunk is Mistral/Pixtral format (OpenAI-like with choices and object)."""
    return (
        "object" in chunk_data
        and chunk_data["object"] == "chat.completion.chunk"
        and "choices" in chunk_data
        and chunk_data["choices"]
        and "message" in chunk_data["choices"][0]
    )


# Format detectors registry (ordered by specificity - most specific first)
_DETECTORS: Dict[str, FormatDetector] = {
    "mistral": _is_mistral_format,  # Specific (has object field)
    "llama": _is_llama_format,  # Specific (has generation field)
    "nova": _is_nova_format,  # Specific (has contentBlockDelta)
    "claude": _is_claude_format,  # Generic (has type field)
}

# Aggregators registry
_AGGREGATORS: Dict[str, ChunkAggregator] = {
    "claude": claude.ClaudeAggregator(),
    "llama": llama.LlamaAggregator(),
    "mistral": mistral.MistralAggregator(),
    "nova": nova.NovaAggregator(),
}


def detect_format(items: List[Dict[str, Any]]) -> str:
    """
    Detect streaming format from the first chunk.

    Args:
        items: List of chunk items from the event stream

    Returns:
        Format name (e.g., "claude", "nova") or "claude" as default
    """
    for item in items:
        if "chunk" not in item:
            continue

        try:
            chunk_data = json.loads(item["chunk"]["bytes"])

            # Try each registered detector
            for format_name, detector in _DETECTORS.items():
                if detector(chunk_data):
                    return format_name

        except (json.JSONDecodeError, KeyError, TypeError):
            continue

    # Default to Claude format
    return "claude"


def get_aggregator(format_name: str) -> ChunkAggregator:
    """
    Get aggregator for the specified format.

    Args:
        format_name: Name of the format

    Returns:
        ChunkAggregator instance

    Raises:
        ValueError: If format is not registered
    """
    if format_name not in _AGGREGATORS:
        raise ValueError(
            f"Unknown format: {format_name}. Registered formats: {list(_AGGREGATORS.keys())}"
        )

    return _AGGREGATORS[format_name]
