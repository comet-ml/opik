"""
Context-learning operations (dataset example stuffing) for Meta-Prompt Optimizer.
"""

# TODO: Move into a shared extension module when prompt learning is generalized.

from collections.abc import Sequence
import logging
import re

import opik

from ....api_objects.types import MetricFunction
from ....utils import token as token_utils
from ....utils.logging import compact_debug_text, debug_log
from ....utils import rng as rng_utils
from .... import constants
from .. import prompts as meta_prompts

logger = logging.getLogger(__name__)


def _sample_dataset_items(
    dataset: opik.Dataset,
    num_examples: int,
    *,
    seed: int | None = None,
) -> Sequence[dict[str, object]]:
    try:
        items = dataset.get_items()
        num_to_sample = min(num_examples, len(items))
        rng = rng_utils.make_rng(seed, "task_context")
        return rng.sample(items, num_to_sample) if len(items) > 0 else []
    except Exception as exc:
        logger.warning("Could not get samples from dataset: %s", exc)
        return []


def _infer_label_field(dataset: opik.Dataset | None) -> str | None:
    if dataset is None:
        return None
    for attr_name in (
        "label_field",
        "answer_field",
        "target_field",
        "ground_truth_field",
    ):
        value = getattr(dataset, attr_name, None)
        if isinstance(value, str) and value:
            return value
    for method_name in ("get_label_field", "get_answer_field", "get_target_field"):
        method = getattr(dataset, method_name, None)
        if callable(method):
            try:
                value = method()
            except Exception:
                value = None
            if isinstance(value, str) and value:
                return value
    schema = getattr(dataset, "schema", None)
    if schema is not None:
        value = getattr(schema, "label", None)
        if isinstance(value, str) and value:
            return value
    return None


def _resolve_input_fields(
    dataset: opik.Dataset | None,
    sample: dict[str, object],
    columns: list[str] | None,
) -> list[str]:
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
    inferred_label = _infer_label_field(dataset)
    if inferred_label:
        excluded_keys.add(inferred_label)
    sensitive_pattern = re.compile(
        r"(^|[_-])("
        r"answer|gold|correct|target|label|output|ground|reference"
        r")([_-]|$)"
    )
    all_input_fields = [k for k in sample.keys() if k not in excluded_keys]
    all_input_fields = [
        k for k in all_input_fields if not sensitive_pattern.search(k.lower())
    ]
    if columns is None:
        return all_input_fields
    input_fields = [f for f in columns if f in all_input_fields]
    if not input_fields:
        logger.warning(
            "None of specified columns %s found in dataset. Using all input fields.",
            columns,
        )
        return all_input_fields
    return input_fields


def _build_metric_context(
    metric: MetricFunction, extract_metric_understanding: bool
) -> str:
    context = ""
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
        context += "Focus on producing clear, correct responses based on the input.\n\n"
    return context


def _normalize_value(value: object, max_value_length: int) -> str:
    value_str = str(value).replace("\n", " ").replace("\r", " ")
    value_str = re.sub(r"\s+", " ", value_str).strip()
    if len(value_str) > max_value_length:
        value_str = value_str[:max_value_length] + "..."
    return value_str


def _build_examples_context(
    samples: Sequence[dict[str, object]],
    input_fields: list[str],
    current_num_examples: int,
    max_value_length: int,
) -> str:
    context = f"Example inputs from dataset ({current_num_examples} samples):\n\n"
    for idx, sample_item in enumerate(samples[:current_num_examples], 1):
        context += f"Example {idx}:\n```\n"
        for key in input_fields:
            value_str = _normalize_value(sample_item.get(key, ""), max_value_length)
            context += (
                f"{meta_prompts.START_DELIM}{key}{meta_prompts.END_DELIM}: "
                f"{value_str}\n"
            )
        context += "```\n\n"
    return context


def _build_context(
    *,
    input_fields: list[str],
    metric: MetricFunction,
    samples: Sequence[dict[str, object]],
    current_num_examples: int,
    max_value_length: int,
    extract_metric_understanding: bool,
) -> str:
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
    context += _build_metric_context(metric, extract_metric_understanding)
    context += _build_examples_context(
        samples, input_fields, current_num_examples, max_value_length
    )
    return context


def get_task_context(
    dataset: opik.Dataset | None,
    metric: MetricFunction,
    num_examples: int = 3,
    columns: list[str] | None = None,
    max_tokens: int = 2000,
    model: str = constants.DEFAULT_MODEL,
    extract_metric_understanding: bool = True,
    seed: int | None = None,
) -> tuple[str, int]:
    """
    Get task-specific context from the dataset and metric configuration.
    Always sanitizes to prevent data leakage. Token-aware with adaptive fitting.
    """
    if dataset is None:
        return "", 0

    samples = _sample_dataset_items(dataset, num_examples, seed=seed)
    if not samples:
        return "", 0

    input_fields = _resolve_input_fields(dataset, samples[0], columns)

    max_value_length = constants.META_PROMPT_DEFAULT_MAX_VALUE_LENGTH
    current_num_examples = len(samples)

    while current_num_examples > 0:
        context = _build_context(
            input_fields=input_fields,
            metric=metric,
            samples=samples,
            current_num_examples=current_num_examples,
            max_value_length=max_value_length,
            extract_metric_understanding=extract_metric_understanding,
        )

        token_count = token_utils.count_tokens(context, model)

        if token_count <= max_tokens:
            debug_log(
                "context_learning",
                tokens=token_count,
                examples=current_num_examples,
                fields=len(input_fields),
                max_value_length=max_value_length,
                max_tokens=max_tokens,
                clipped=0,
            )
            debug_log(
                "context_learning_preview",
                text=compact_debug_text(context, limit=240),
            )
            return context, token_count

        if current_num_examples > 1:
            current_num_examples -= 1
            debug_log(
                "context_learning_reduce_examples",
                examples=current_num_examples,
                tokens=token_count,
            )
        elif max_value_length > constants.META_PROMPT_MIN_VALUE_LENGTH:
            max_value_length = max(
                constants.META_PROMPT_MIN_VALUE_LENGTH,
                max_value_length - constants.META_PROMPT_VALUE_LENGTH_REDUCTION_STEP,
            )
            debug_log(
                "context_learning_reduce_truncation",
                max_value_length=max_value_length,
                tokens=token_count,
            )
        else:
            logger.warning(
                "Cannot fit task context within %s tokens (currently %s). Returning minimal context.",
                max_tokens,
                token_count,
            )
            return context, token_count

    return "", 0


def calculate_max_context_tokens(model: str) -> int:
    """
    Calculate token budget for task context data stuffing (dataset examples ONLY).

    Uses ~25% of model's max tokens, capped at default dataset context max.
    Falls back to absolute max for custom models where litellm can't determine limits.
    """
    try:
        model_max_tokens: int = token_utils.get_max_tokens(model)  # type: ignore[assignment]
        calculated_max = int(
            model_max_tokens * constants.META_PROMPT_DEFAULT_DATASET_CONTEXT_RATIO
        )
        logger.debug(
            "Model %s max tokens: %s, calculated dataset context budget: %s",
            model,
            model_max_tokens,
            calculated_max,
        )
    except Exception as exc:
        logger.debug(
            "Could not get max tokens for model %s: %s. Using default max: %s",
            model,
            exc,
            constants.META_PROMPT_DEFAULT_DATASET_CONTEXT_MAX_TOKENS,
        )
        calculated_max = constants.META_PROMPT_DEFAULT_DATASET_CONTEXT_MAX_TOKENS

    return min(calculated_max, constants.META_PROMPT_DEFAULT_DATASET_CONTEXT_MAX_TOKENS)
