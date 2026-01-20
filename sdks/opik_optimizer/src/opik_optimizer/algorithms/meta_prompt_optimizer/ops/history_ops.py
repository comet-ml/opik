"""
History and context operations for the Meta-Prompt Optimizer.
"""

from typing import Any
from collections.abc import Sequence
import logging
import random
import re

import opik

from ....api_objects.types import MetricFunction
from ....core import runtime
from ....core.results import OptimizationRound, round_payload
from ....core.state import OptimizationContext
from .. import prompts as meta_prompts


def record_round_history(
    *,
    optimizer: Any,
    context: OptimizationContext,
    round_num: int,
    prompt_scores: list[tuple[Any, float]],
    best_cand_score_avg: float,
    best_candidate_this_round: Any,
    improvement: float,
) -> None:
    """Record trials and finalize the round history entry."""
    round_handle = optimizer.pre_round(context)
    optimizer.set_selection_meta(
        {
            "selection_policy": optimizer.selection_strategy,
            "score_used": best_cand_score_avg,
            "candidate_count": len(prompt_scores),
        }
    )
    for cand_prompt, cand_score in prompt_scores:
        runtime.record_and_post_trial(
            optimizer=optimizer,
            context=context,
            prompt_or_payload=cand_prompt,
            score=cand_score,
            candidate_id=f"round{round_num}_cand",
            metrics={"selection_score": cand_score},
            round_handle=round_handle,
            post_metrics=None,
            post_extras={"round_num": round_num},
        )
    optimizer.post_round(
        round_handle=round_handle,
        context=context,
        best_score=best_cand_score_avg,
        best_candidate=best_candidate_this_round,
        stop_reason=context.finish_reason if context.should_stop else None,
        extras={
            "improvement": improvement,
            "best_so_far": context.current_best_score,
        },
    )


# Constants for adaptive context fitting
DEFAULT_MAX_VALUE_LENGTH = 2000  # Initial truncation limit for field values
MIN_VALUE_LENGTH = 200  # Minimum truncation limit before giving up
VALUE_LENGTH_REDUCTION_STEP = 200  # How much to reduce truncation by each iteration
DEFAULT_TOP_PROMPTS_PER_RECENT_ROUND = (
    4  # Max of default synthesis and generation counts
)

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
            messages = [{"role": "user", "content": text}]
            return token_counter(model=model, messages=messages)
        except Exception as exc:
            logger.debug("litellm token_counter failed: %s, using fallback", exc)

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
    """
    if dataset is None:
        return "", 0

    samples = []
    try:
        items = dataset.get_items()
        num_to_sample = min(num_examples, len(items))
        samples = random.sample(items, num_to_sample) if len(items) > 0 else []
    except Exception as exc:
        logger.warning("Could not get samples from dataset: %s", exc)
        return "", 0

    if not samples:
        return "", 0

    sample = samples[0]

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
        "supporting_facts",
    }

    all_input_fields = [k for k in sample.keys() if k not in excluded_keys]

    if columns is not None:
        input_fields = [f for f in columns if f in all_input_fields]
        if not input_fields:
            logger.warning(
                "None of specified columns %s found in dataset. Using all input fields.",
                columns,
            )
            input_fields = all_input_fields
    else:
        input_fields = all_input_fields

    max_value_length = DEFAULT_MAX_VALUE_LENGTH
    current_num_examples = len(samples)

    while current_num_examples > 0:
        context = "Task Context: "
        context += (
            "Available input variables (use "
            f"{meta_prompts.START_DELIM}variable_name{meta_prompts.END_DELIM}"
            " syntax): "
        )
        context += ", ".join(
            [
                f"{meta_prompts.START_DELIM}{field}{meta_prompts.END_DELIM}"
                for field in input_fields
            ]
        )
        context += "\n\n"

        if extract_metric_understanding:
            metric_name = metric.__name__
            metric_doc = getattr(metric, "__doc__", None)
            metric_direction = getattr(metric, "direction", None)

            context += "Evaluation Metric:\n"
            context += f"- Name: {metric_name}\n"

            if metric_direction:
                goal = "Maximize" if metric_direction == "maximize" else "Minimize"
                context += f"- Goal: {goal} this metric\n"

            if metric_doc and metric_doc.strip():
                doc_lines = metric_doc.strip().split("\n")
                description = next(
                    (line.strip() for line in doc_lines if line.strip()), None
                )
                if description:
                    context += f"- Description: {description}\n"

            context += "\nFocus on producing clear, correct responses that optimize for this metric.\n\n"
        else:
            context += (
                "Evaluation: Your output will be evaluated for accuracy and quality.\n"
            )
            context += (
                "Focus on producing clear, correct responses based on the input.\n\n"
            )

        context += f"Example inputs from dataset ({current_num_examples} samples):\n\n"
        for idx, sample_item in enumerate(samples[:current_num_examples], 1):
            context += f"Example {idx}:\n```\n"
            for key in input_fields:
                value = sample_item.get(key, "")
                value_str = str(value).replace("\n", " ").replace("\r", " ")
                value_str = re.sub(r"\s+", " ", value_str).strip()
                if len(value_str) > max_value_length:
                    value_str = value_str[:max_value_length] + "..."
                context += (
                    f"{meta_prompts.START_DELIM}{key}{meta_prompts.END_DELIM}: "
                    f"{value_str}\n"
                )
            context += "```\n\n"

        token_count = count_tokens(context, model)

        if token_count <= max_tokens:
            logger.debug(
                "Task context: %s tokens, %s examples, %s fields, max_value_length=%s",
                token_count,
                current_num_examples,
                len(input_fields),
                max_value_length,
            )
            return context, token_count

        if current_num_examples > 1:
            current_num_examples -= 1
            logger.debug(
                "Reducing examples to %s (was %s tokens)",
                current_num_examples,
                token_count,
            )
        elif max_value_length > MIN_VALUE_LENGTH:
            max_value_length = max(
                MIN_VALUE_LENGTH, max_value_length - VALUE_LENGTH_REDUCTION_STEP
            )
            logger.debug(
                "Reducing truncation to %s chars (was %s tokens)",
                max_value_length,
                token_count,
            )
        else:
            logger.warning(
                "Cannot fit task context within %s tokens (currently %s). Returning minimal context.",
                max_tokens,
                token_count,
            )
            return context, token_count

    return "", 0


def build_history_context(
    previous_rounds: Sequence[OptimizationRound],
    hall_of_fame: Any | None = None,
    pretty_mode: bool = True,
    top_prompts_per_round: int = DEFAULT_TOP_PROMPTS_PER_RECENT_ROUND,
) -> str:
    """
    Build context from Hall of Fame (if available) or previous optimization rounds.
    """
    context = ""

    if hall_of_fame and hasattr(hall_of_fame, "entries") and hall_of_fame.entries:
        context += "\nHall of Fame: Best Performing Prompts Across All Rounds:\n"
        context += "=" * 80 + "\n"
        context += (
            "Study these top performers carefully - what patterns make them successful?\n\n"
        )

        for i, entry in enumerate(hall_of_fame.entries[:5], 1):
            improvement_pct = entry.improvement_over_baseline * 100
            context += (
                f"\n#{i} WINNER | Trial {entry.trial_number} | "
                f"Score: {entry.score:.4f} | Improvement: {improvement_pct:+.1f}%\n"
            )

            if pretty_mode:
                context += "Full Prompt Messages:\n"
                prompt_lines: list[str] = []
                for msg in entry.prompt_messages:
                    role = msg.get("role", "unknown")
                    msg_content = msg.get("content", "")
                    prompt_lines.append("  [" + role.upper() + "]: " + msg_content)
                context += "\n".join(prompt_lines) + "\n\n"
            else:
                import json

                context += "Prompt:\n"
                context += json.dumps(entry.prompt_messages, indent=2)
                context += "\n"

            if entry.extracted_patterns:
                context += f"Why it worked: {', '.join(entry.extracted_patterns)}\n"

        context += "\n" + "=" * 80 + "\n"

    rounds_list = list(previous_rounds)

    if rounds_list:
        context += "\nRecent Rounds - What We Just Tried:\n"
        context += "=" * 80 + "\n"
        context += (
            "CRITICAL: These are the prompts we JUST generated in recent rounds.\n"
        )
        context += (
            "- If scores are LOWER than Hall of Fame: identify what's missing and avoid repeating these patterns\n"
        )
        context += (
            "- If scores are declining: we're moving in the wrong direction, course correct\n"
        )
        context += (
            "- Compare against winners: what did recent attempts lack? What mistakes were made?\n"
        )
        context += (
            "- DO NOT generate similar variations of recent low-scoring prompts\n\n"
        )
        for round_data in reversed(rounds_list[-3:]):
            payload = round_payload(round_data)
            round_index = payload.get("round_index", 0)
            best_score = payload.get("best_score", float("nan"))
            context += f"\nRound {round_index + 1}:\n"
            context += f"Best score this round: {best_score:.4f}\n"
            context += "Top prompts generated:\n"

            generated = (
                payload.get("generated_prompts") or payload.get("candidates") or []
            )
            sorted_generated = sorted(
                generated,
                key=lambda p: p.get("score", -float("inf")),
                reverse=True,
            )

            for p in sorted_generated[:top_prompts_per_round]:
                prompt_data = p.get("candidate") or p.get("prompt", "N/A")
                score = p.get("score", float("nan"))
                context += f"- Score {score:.4f}:\n"

                if isinstance(prompt_data, list):
                    if pretty_mode:
                        lines: list[str] = []
                        for msg in prompt_data:
                            role = msg.get("role", "unknown")
                            msg_content = msg.get("content", "")
                            lines.append("  [" + role.upper() + "]: " + msg_content)
                        context += "\n".join(lines) + "\n\n"
                    else:
                        import json

                        context += json.dumps(prompt_data, indent=2)
                        context += "\n\n"
                else:
                    context += f"{prompt_data}\n\n"

    return context
