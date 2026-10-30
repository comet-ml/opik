"""
Context-learning operations (dataset example stuffing) for Meta-Prompt Optimizer.
"""

# TODO: Move into a shared extension module when prompt learning is generalized.

from collections.abc import Sequence
import logging

import opik

from ....api_objects.evidence import EvidenceCase
from ....api_objects.types import MetricFunction
from ....utils.feedback import is_label_field
from ....utils.logging import debug_log
from ....utils import rng as rng_utils
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
    inferred_label = _infer_label_field(dataset)
    all_input_fields = [
        k for k in sample.keys() if k != inferred_label and not is_label_field(k)
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


def _build_context(
    *,
    input_fields: list[str],
    metric: MetricFunction,
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
    return context


def get_task_context(
    dataset: opik.Dataset | None,
    metric: MetricFunction,
    num_examples: int = 3,
    columns: list[str] | None = None,
    extract_metric_understanding: bool = True,
    seed: int | None = None,
) -> tuple[str, list[EvidenceCase]]:
    """Describe the task and pick training examples to show as evidence cases.

    Label fields never leave the dataset; example rendering and size limits
    are handled by the shared evidence renderer.
    """
    if dataset is None:
        return "", []

    samples = _sample_dataset_items(dataset, num_examples, seed=seed)
    if not samples:
        return "", []

    input_fields = _resolve_input_fields(dataset, samples[0], columns)
    context = _build_context(
        input_fields=input_fields,
        metric=metric,
        extract_metric_understanding=extract_metric_understanding,
    )
    cases = [
        EvidenceCase(
            case_id=str(sample.get("id", index)),
            inputs={key: sample[key] for key in input_fields if key in sample},
        )
        for index, sample in enumerate(samples)
    ]
    debug_log("context_learning", examples=len(cases), fields=len(input_fields))
    return context, cases
