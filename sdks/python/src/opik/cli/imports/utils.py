"""Common utilities for import functionality."""

from typing import Any, Dict, Optional

import opik
from rich.console import Console

console = Console()


def matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using case-insensitive substring matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


def translate_trace_id(
    original_trace_id: str, trace_id_map: Optional[Dict[str, str]]
) -> Optional[str]:
    """Translate an original trace id from export to the newly created id.

    Returns None if mapping is unavailable for this trace id.
    """
    if trace_id_map is None:
        return None
    return trace_id_map.get(original_trace_id)


def find_dataset_item_by_content(
    dataset: opik.Dataset, expected_content: Dict[str, Any]
) -> Optional[str]:
    """Find a dataset item by matching its content."""
    try:
        items = dataset.get_items()
        for item in items:
            # Compare key fields for matching
            if item.get("input") == expected_content.get("input") and item.get(
                "expected_output"
            ) == expected_content.get("expected_output"):
                return item.get("id")
    except Exception:
        pass
    return None


def create_dataset_item(dataset: opik.Dataset, item_data: Dict[str, Any]) -> str:
    """Create a dataset item and return its ID."""
    new_item = {
        "input": item_data.get("input"),
        "expected_output": item_data.get("expected_output"),
        "metadata": item_data.get("metadata"),
    }

    dataset.insert([new_item])

    # Find the newly created item
    items = dataset.get_items()
    for item in items:
        if (
            item.get("input") == new_item["input"]
            and item.get("expected_output") == new_item["expected_output"]
        ):
            item_id = item.get("id")
            if item_id is not None:
                return item_id

    dataset_name = getattr(dataset, "name", None)
    dataset_info = f", Dataset: {dataset_name!r}" if dataset_name else ""
    raise Exception(
        f"Failed to create dataset item. "
        f"Input: {new_item.get('input')!r}, "
        f"Expected Output: {new_item.get('expected_output')!r}{dataset_info}"
    )


def handle_trace_reference(item_data: Dict[str, Any]) -> Optional[str]:
    """Handle trace references from deduplicated exports."""
    trace_reference = item_data.get("trace_reference")
    if trace_reference:
        trace_id = trace_reference.get("trace_id")
        console.print(f"[blue]Using trace reference: {trace_id}[/blue]")
        return trace_id

    # Fall back to direct trace_id
    return item_data.get("trace_id")
