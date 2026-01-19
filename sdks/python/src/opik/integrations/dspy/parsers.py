"""
Parsers and data structures for extracting information from DSPy LM responses.

This module contains utilities for parsing DSPy LM history entries and
extracting relevant information like usage, provider, and cost data.
"""

from dataclasses import dataclass
from typing import Any, Optional
import logging

import dspy

from opik import llm_usage, types

LOGGER = logging.getLogger(__name__)


@dataclass
class LMHistoryInfo:
    """
    Information extracted from a DSPy LM history entry.

    This dataclass holds the parsed information from an LM call's history,
    including usage statistics, cache status, provider information, and cost.

    Attributes:
        usage: Token usage information (prompt, completion, total tokens)
        cache_hit: Whether the response was served from cache.
            True if cached, False if not, None if unknown.
        actual_provider: The actual provider that served the request.
            This is useful for LLM routers like OpenRouter that may route
            to different underlying providers (e.g., "Novita", "Together").
        actual_model: The actual model that served the request.
            This is useful for LLM routers like OpenRouter when using presets
            (e.g., "@preset/qwen" resolves to "qwen/qwen3-235b-a22b-2507").
        total_cost: The total cost of the request from the provider.
            This includes accurate pricing for all token types.
    """

    usage: Optional[llm_usage.OpikUsage]
    cache_hit: Optional[bool]
    actual_provider: Optional[str]
    actual_model: Optional[str]
    total_cost: Optional[float]


def get_span_type(instance: Any) -> types.SpanType:
    """
    Determine the span type based on the DSPy instance type.

    Args:
        instance: A DSPy module, LM, or tool instance.

    Returns:
        The appropriate span type: "llm" for Predict/LM, "tool" for Tool,
        or "general" for other types.
    """
    if isinstance(instance, dspy.Predict):
        return "llm"
    elif isinstance(instance, dspy.LM):
        return "llm"
    elif isinstance(instance, dspy.Tool):
        return "tool"
    return "general"


def extract_lm_info_from_history(
    lm_instance: Any,
    expected_messages: Optional[Any],
) -> LMHistoryInfo:
    """
    Extract token usage, cache status, actual provider, and cost from the LM's history.

    DSPy stores usage information in the LM's history after each call.
    We verify the history entry matches our expected messages to handle
    potential race conditions with concurrent LM calls.

    For routers like OpenRouter, the response contains the actual provider
    that served the request (e.g., "Novita", "Together"), which differs from
    the router name used in the model string (e.g., "openrouter").

    The cost field is provided by providers like OpenRouter and includes
    accurate pricing for all token types (reasoning, cache, multimodal).

    Args:
        lm_instance: The DSPy LM instance that has the history.
        expected_messages: The expected messages to match in the history entry.

    Returns:
        LMHistoryInfo containing usage, cache_hit, actual_provider, and total_cost.
    """
    empty_result = LMHistoryInfo(
        usage=None,
        cache_hit=None,
        actual_provider=None,
        actual_model=None,
        total_cost=None,
    )

    if not hasattr(lm_instance, "history") or not lm_instance.history:
        return empty_result

    try:
        last_entry = lm_instance.history[-1]

        # Verify we have the correct history entry by checking messages match
        if last_entry.get("messages") != expected_messages:
            LOGGER.debug(
                "History entry messages don't match expected messages, "
                "skipping usage extraction (possibly due to concurrent LM calls)"
            )
            return empty_result

        response = last_entry.get("response")
        usage_dict = last_entry.get("usage")

        # Extract actual provider and model from response (useful for routers like OpenRouter)
        # The response is a LiteLLM ModelResponse object with 'provider' and 'model' attributes
        # when using routers like OpenRouter
        actual_provider: Optional[str] = None
        actual_model: Optional[str] = None
        if response is not None:
            if hasattr(response, "provider"):
                actual_provider = response.provider
            if hasattr(response, "model"):
                actual_model = response.model

        # Extract cost from history entry or usage dict
        # OpenRouter and other providers return accurate cost including all token types
        total_cost: Optional[float] = None
        if (cost := last_entry.get("cost") or 0) > 0:
            total_cost = cost
        elif usage_dict and (cost := usage_dict.get("cost") or 0) > 0:
            total_cost = cost

        # Get explicit cache_hit if set, otherwise infer from usage (empty = cached)
        if response is None:
            cache_hit = not usage_dict
        elif hasattr(response, "cache_hit") and response.cache_hit is not None:
            cache_hit = response.cache_hit
        else:
            # Fallback: infer from usage (empty = cached)
            cache_hit = not usage_dict

        if usage_dict:
            usage = llm_usage.build_opik_usage_from_unknown_provider(usage_dict)
            return LMHistoryInfo(
                usage=usage,
                cache_hit=cache_hit,
                actual_provider=actual_provider,
                actual_model=actual_model,
                total_cost=total_cost,
            )
        else:
            return LMHistoryInfo(
                usage=None,
                cache_hit=cache_hit,
                actual_provider=actual_provider,
                actual_model=actual_model,
                total_cost=None,
            )
    except Exception:
        LOGGER.debug(
            "Failed to extract info from DSPy LM history",
            exc_info=True,
        )
        return empty_result
