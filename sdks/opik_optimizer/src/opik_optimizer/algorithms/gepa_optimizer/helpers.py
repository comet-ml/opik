"""Helper utilities for GEPA Optimizer.

Contains utility functions for genetic algorithm operations,
Pareto optimization helpers, and other utility functions.
"""

from typing import Any

from opik import Dataset

from .types import OpikDataInst


def build_data_insts(
    dataset_items: list[dict[str, Any]],
    input_key: str,
    output_key: str,
) -> list[OpikDataInst]:
    """
    Build OpikDataInst objects from dataset items.

    Args:
        dataset_items: List of dataset item dictionaries
        input_key: Key in dataset items for input text
        output_key: Key in dataset items for output/answer text

    Returns:
        List of OpikDataInst objects
    """
    data_insts: list[OpikDataInst] = []
    for item in dataset_items:
        additional_context: dict[str, str] = {}
        metadata = item.get("metadata") or {}
        if isinstance(metadata, dict):
            context_value = metadata.get("context")
            if isinstance(context_value, str):
                additional_context["context"] = context_value
        if "context" in item and isinstance(item["context"], str):
            additional_context.setdefault("context", item["context"])

        data_insts.append(
            OpikDataInst(
                input_text=str(item.get(input_key, "")),
                answer=str(item.get(output_key, "")),
                additional_context=additional_context,
                opik_item=item,
            )
        )
    return data_insts


def infer_dataset_keys(dataset: Dataset) -> tuple[str, str]:
    """
    Infer input and output keys from a dataset by examining sample items.

    Args:
        dataset: The dataset to examine

    Returns:
        Tuple of (input_key, output_key)
    """
    items = dataset.get_items(1)
    if not items:
        return "text", "label"
    sample = items[0]
    output_candidates = ["label", "answer", "output", "expected_output"]
    output_key = next((k for k in output_candidates if k in sample), "label")
    excluded = {output_key, "id", "metadata"}
    input_key = next((k for k in sample.keys() if k not in excluded), "text")
    return input_key, output_key
