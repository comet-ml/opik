"""
Context building operations for the Meta-Prompt Optimizer.

This module contains functions for building task context and history context.
"""

from typing import Any
import logging
import random
import re

import opik
from ....base_optimizer import OptimizationRound
from ....api_objects.types import MetricFunction
from ..prompts import START_DELIM, END_DELIM

logger = logging.getLogger(__name__)

# Constants for adaptive context fitting
DEFAULT_MAX_VALUE_LENGTH = 2000  # Initial truncation limit for field values
MIN_VALUE_LENGTH = 200  # Minimum truncation limit before giving up
VALUE_LENGTH_REDUCTION_STEP = 200  # How much to reduce truncation by each iteration
DEFAULT_TOP_PROMPTS_PER_RECENT_ROUND = (
    4  # Max of default synthesis and generation counts
)

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
    metric: MetricFunction,
    num_examples: int = 3,
    columns: list[str] | None = None,
    max_tokens: int = 2000,
    model: str = "gpt-4",
    extract_metric_understanding: bool = True,
) -> tuple[str, int]:
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
        verbose: Verbosity level for display output (default: 1)
        extract_metric_understanding: Extract and display metric name, direction, description

    Returns:
        Tuple of (sanitized task context string, actual token count used)
    """
    if dataset is None:
        return "", 0

    samples = []
    try:
        # Get multiple samples for better context
        items = dataset.get_items()
        # Randomly sample to show diverse examples across rounds
        num_to_sample = min(num_examples, len(items))
        samples = random.sample(items, num_to_sample) if len(items) > 0 else []
    except Exception as e:
        logger.warning(f"Could not get samples from dataset: {e}")
        return "", 0

    if not samples:
        return "", 0

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
        "supporting_facts",  # This reveals which context parts contain the answer (data leakage)
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
    max_value_length = DEFAULT_MAX_VALUE_LENGTH
    current_num_examples = len(samples)

    while current_num_examples > 0:
        # Build context string
        context = "Task Context: "
        context += f"Available input variables (use {START_DELIM}variable_name{END_DELIM} syntax): "
        context += ", ".join(
            [f"{START_DELIM}{field}{END_DELIM}" for field in input_fields]
        )
        context += "\n\n"

        # Conditionally extract and add metric information
        if extract_metric_understanding:
            # Extract metric information
            metric_name = metric.__name__
            metric_doc = getattr(metric, "__doc__", None)
            metric_direction = getattr(metric, "direction", None)

            # Add metric information to context
            context += "Evaluation Metric:\n"
            context += f"- Name: {metric_name}\n"

            if metric_direction:
                context += f"- Goal: {'Maximize' if metric_direction == 'maximize' else 'Minimize'} this metric\n"

            if metric_doc and metric_doc.strip():
                # Clean up the docstring
                doc_lines = metric_doc.strip().split("\n")
                # Take first meaningful line (skip empty lines)
                description = next(
                    (line.strip() for line in doc_lines if line.strip()), None
                )
                if description:
                    context += f"- Description: {description}\n"

            context += "\nFocus on producing clear, correct responses that optimize for this metric.\n\n"
        else:
            # Generic evaluation message without metric details
            context += (
                "Evaluation: Your output will be evaluated for accuracy and quality.\n"
            )
            context += (
                "Focus on producing clear, correct responses based on the input.\n\n"
            )

        # Show multiple sanitized examples
        context += f"Example inputs from dataset ({current_num_examples} samples):\n\n"
        for idx, sample_item in enumerate(samples[:current_num_examples], 1):
            context += f"Example {idx}:\n```\n"
            for key in input_fields:
                value = sample_item.get(key, "")
                # Convert to string and remove all newlines and excessive whitespace
                value_str = str(value).replace("\n", " ").replace("\r", " ")
                # Collapse multiple spaces into single space
                value_str = re.sub(r"\s+", " ", value_str).strip()
                # Truncate long values
                if len(value_str) > max_value_length:
                    value_str = value_str[:max_value_length] + "..."
                context += f"{START_DELIM}{key}{END_DELIM}: {value_str}\n"
            context += "```\n\n"

        # Count tokens
        token_count = count_tokens(context, model)

        if token_count <= max_tokens:
            logger.debug(
                f"Task context: {token_count} tokens, {current_num_examples} examples, "
                f"{len(input_fields)} fields, max_value_length={max_value_length}"
            )

            return context, token_count

        # Over budget - try to reduce
        if current_num_examples > 1:
            # First, reduce number of examples
            current_num_examples -= 1
            logger.debug(
                f"Reducing examples to {current_num_examples} (was {token_count} tokens)"
            )
        elif max_value_length > MIN_VALUE_LENGTH:
            # If only 1 example left, reduce truncation limit more gradually
            max_value_length = max(
                MIN_VALUE_LENGTH, max_value_length - VALUE_LENGTH_REDUCTION_STEP
            )
            logger.debug(
                f"Reducing truncation to {max_value_length} chars (was {token_count} tokens)"
            )
        else:
            # Cannot reduce further - return what we have
            logger.warning(
                f"Cannot fit task context within {max_tokens} tokens (currently {token_count}). "
                f"Returning minimal context."
            )
            return context, token_count

    # Fallback if we somehow exit the loop
    return "", 0


def build_history_context(
    previous_rounds: list[OptimizationRound],
    hall_of_fame: Any | None = None,
    pretty_mode: bool = True,
    top_prompts_per_round: int = DEFAULT_TOP_PROMPTS_PER_RECENT_ROUND,
) -> str:
    """
    Build context from Hall of Fame (if available) or previous optimization rounds.

    Args:
        previous_rounds: List of previous optimization rounds
        hall_of_fame: Optional Hall of Fame object with top-performing prompts
        pretty_mode: If True, show pretty formatted; if False, show as JSON
        top_prompts_per_round: How many prompts to display from each recent round

    Returns:
        History context string
    """
    context = ""

    # Prioritize Hall of Fame entries - show the BEST prompts across ALL rounds
    if hall_of_fame and hasattr(hall_of_fame, "entries") and hall_of_fame.entries:
        context += "\nHall of Fame: Best Performing Prompts Across All Rounds:\n"
        context += "=" * 80 + "\n"
        context += "Study these top performers carefully - what patterns make them successful?\n\n"

        # Show top 5 entries with FULL prompts (we have plenty of token budget)
        for i, entry in enumerate(hall_of_fame.entries[:5], 1):
            improvement_pct = entry.improvement_over_baseline * 100
            context += f"\n#{i} WINNER | Trial {entry.trial_number} | Score: {entry.score:.4f} | Improvement: {improvement_pct:+.1f}%\n"

            # Show prompt based on display preference
            if pretty_mode:
                # Show prompt in pretty formatted style
                context += "Full Prompt Messages:\n"
                prompt_lines: list[str] = []
                for msg in entry.prompt_messages:
                    role = msg.get("role", "unknown")
                    msg_content = msg.get("content", "")
                    # Don't use f-string here - content may have template variables like {question}, {context}
                    prompt_lines.append("  [" + role.upper() + "]: " + msg_content)
                context += "\n".join(prompt_lines) + "\n\n"
            else:
                # Show prompt in JSON format (same as output format)
                import json

                context += "Prompt:\n"
                context += json.dumps(entry.prompt_messages, indent=2)
                context += "\n"

            # Show extracted patterns if available
            if entry.extracted_patterns:
                context += f"Why it worked: {', '.join(entry.extracted_patterns)}\n"

        context += "\n" + "=" * 80 + "\n"

    # Also show recent rounds for temporal context (last 3 rounds)
    if previous_rounds:
        context += "\nRecent Rounds - What We Just Tried:\n"
        context += "=" * 80 + "\n"
        context += (
            "CRITICAL: These are the prompts we JUST generated in recent rounds.\n"
        )
        context += "- If scores are LOWER than Hall of Fame: identify what's missing and avoid repeating these patterns\n"
        context += "- If scores are declining: we're moving in the wrong direction, course correct\n"
        context += "- Compare against winners: what did recent attempts lack? What mistakes were made?\n"
        context += (
            "- DO NOT generate similar variations of recent low-scoring prompts\n\n"
        )
        for round_data in reversed(previous_rounds[-3:]):
            context += f"\nRound {round_data.round_number}:\n"
            context += f"Best score this round: {round_data.best_score:.4f}\n"
            context += "Top prompts generated:\n"

            sorted_generated = sorted(
                round_data.generated_prompts,
                key=lambda p: p.get("score", -float("inf")),
                reverse=True,
            )

            # Show top prompts per round
            for p in sorted_generated[:top_prompts_per_round]:
                prompt_data = p.get("prompt", "N/A")
                score = p.get("score", float("nan"))
                context += f"- Score {score:.4f}:\n"

                # Handle both message list format and string format
                if isinstance(prompt_data, list):
                    # It's a list of message dicts - apply pretty mode
                    if pretty_mode:
                        # Pretty formatted style
                        lines: list[str] = []
                        for msg in prompt_data:
                            role = msg.get("role", "unknown")
                            msg_content = msg.get("content", "")
                            # Don't use f-string here - content may have template variables like {question}, {context}
                            lines.append("  [" + role.upper() + "]: " + msg_content)
                        context += "\n".join(lines) + "\n\n"
                    else:
                        # JSON format
                        import json

                        context += json.dumps(prompt_data, indent=2)
                        context += "\n\n"
                else:
                    # It's already a string, just show it
                    context += f"{prompt_data}\n\n"

    return context
