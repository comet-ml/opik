"""
Context building operations for the Meta-Prompt Optimizer.

This module contains functions for building task context and history context.
"""

from collections.abc import Callable
import logging

import opik
from ....base_optimizer import OptimizationRound
from ..prompts import START_DELIM, END_DELIM

logger = logging.getLogger(__name__)

# Token counting with litellm
try:
    from litellm import token_counter

    LITELLM_TOKEN_COUNTER_AVAILABLE = True
except ImportError:
    LITELLM_TOKEN_COUNTER_AVAILABLE = False
    logger.warning(
        "litellm token_counter not available - token counting will be approximate"
    )


def count_tokens(text: str, model: str = "gpt-4") -> int:
    """Count tokens in text using litellm's token_counter or fallback approximation."""
    if LITELLM_TOKEN_COUNTER_AVAILABLE:
        try:
            # litellm token_counter expects messages format
            messages = [{"role": "user", "content": text}]
            return token_counter(model=model, messages=messages)
        except Exception as e:
            logger.debug(f"litellm token_counter failed: {e}, using fallback")

    # Fallback: rough approximation (1 token ≈ 4 chars)
    return len(text) // 4


def get_task_context(
    dataset: opik.Dataset | None,
    metric: Callable,
    num_examples: int = 3,
    columns: list[str] | None = None,
    max_tokens: int = 2000,
    model: str = "gpt-4",
) -> str:
    """
    Get task-specific context from the dataset and metric configuration.
    Always sanitizes to prevent data leakage. Token-aware with adaptive fitting.

    Args:
        dataset: The dataset to extract context from
        metric: The evaluation metric
        num_examples: Number of dataset examples to show (default: 3)
        columns: Specific columns to include (None = all input columns)
        max_tokens: Token budget for dataset examples ONLY (adaptive fitting limit)
        model: Model name for token counting (default: gpt-4)

    Returns:
        Sanitized task context string that fits within token budget
    """
    if dataset is None:
        return ""

    samples = []
    try:
        # Get multiple samples for better context
        items = dataset.get_items()
        samples = items[: min(num_examples, len(items))]
    except Exception as e:
        logger.warning(f"Could not get samples from dataset: {e}")
        return ""

    if not samples:
        return ""

    # Use first sample to determine field structure
    sample = samples[0]

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

    # Determine which fields to show
    all_input_fields = [k for k in sample.keys() if k not in excluded_keys]

    # Filter to specified columns if provided
    if columns is not None:
        input_fields = [f for f in columns if f in all_input_fields]
        if not input_fields:
            logger.warning(
                f"None of specified columns {columns} found in dataset. Using all input fields."
            )
            input_fields = all_input_fields
    else:
        input_fields = all_input_fields

    # Build context with adaptive fitting
    max_value_length = 150  # Start with this truncation limit
    current_num_examples = len(samples)

    while current_num_examples > 0:
        # Build context string
        context = "\nTask Context:\n"
        context += f"Available input variables (use {START_DELIM}variable_name{END_DELIM} syntax): "
        context += ", ".join(
            [f"{START_DELIM}{field}{END_DELIM}" for field in input_fields]
        )
        context += "\n\n"

        # Generic metric description
        context += (
            "Evaluation: Your output will be evaluated for accuracy and quality.\n"
        )
        context += "Focus on producing clear, correct responses based on the input.\n\n"

        # Show multiple sanitized examples
        context += f"Example inputs from dataset ({current_num_examples} samples):\n\n"
        for idx, sample_item in enumerate(samples[:current_num_examples], 1):
            context += f"Example {idx}:\n"
            for key in input_fields:
                value = sample_item.get(key, "")
                # Truncate long values
                value_str = (
                    str(value)[:max_value_length] + "..."
                    if len(str(value)) > max_value_length
                    else str(value)
                )
                context += f"  {START_DELIM}{key}{END_DELIM}: {value_str}\n"
            context += "\n"

        # Count tokens
        token_count = count_tokens(context, model)

        if token_count <= max_tokens:
            logger.debug(
                f"Task context: {token_count} tokens, {current_num_examples} examples, "
                f"{len(input_fields)} fields, max_value_length={max_value_length}"
            )
            return context

        # Over budget - try to reduce
        if current_num_examples > 1:
            # First, reduce number of examples
            current_num_examples -= 1
            logger.debug(
                f"Reducing examples to {current_num_examples} (was {token_count} tokens)"
            )
        elif max_value_length > 50:
            # If only 1 example left, reduce truncation limit
            max_value_length = max(50, max_value_length - 50)
            logger.debug(
                f"Reducing truncation to {max_value_length} chars (was {token_count} tokens)"
            )
        else:
            # Cannot reduce further - return what we have
            logger.warning(
                f"Cannot fit task context within {max_tokens} tokens (currently {token_count}). "
                f"Returning minimal context."
            )
            return context

    # Fallback if we somehow exit the loop
    return ""


def build_history_context(previous_rounds: list[OptimizationRound]) -> str:
    """
    Build context from previous optimization rounds.

    Args:
        previous_rounds: List of previous optimization rounds

    Returns:
        History context string
    """
    if not previous_rounds:
        return ""

    context = "\nPrevious rounds (latest first):\n"
    for round_data in reversed(previous_rounds[-3:]):
        context += f"\nRound {round_data.round_number}:\n"
        context += f"Best score this round: {round_data.best_score:.4f}\n"
        context += "Generated prompts this round (best first):\n"

        sorted_generated = sorted(
            round_data.generated_prompts,
            key=lambda p: p.get("score", -float("inf")),
            reverse=True,
        )

        for p in sorted_generated[:3]:
            prompt_text = p.get("prompt", "N/A")
            score = p.get("score", float("nan"))
            context += f"- Prompt: {prompt_text[:150]}...\n"
            context += f"  Avg Score: {score:.4f}\n"
    return context
