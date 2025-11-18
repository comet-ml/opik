"""Common utilities for import functionality."""

from typing import Any, Dict, List, Optional

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
    """Find a dataset item by matching its content.

    Compares all fields in expected_content (excluding 'id') with dataset items.
    """
    try:
        items = dataset.get_items()
        # Remove 'id' from expected_content for comparison
        expected_without_id = {k: v for k, v in expected_content.items() if k != "id"}

        for item in items:
            # Compare all fields (excluding 'id')
            item_without_id = {k: v for k, v in item.items() if k != "id"}
            if item_without_id == expected_without_id:
                return item.get("id")
    except Exception:
        pass
    return None


def create_dataset_item(dataset: opik.Dataset, item_data: Dict[str, Any]) -> str:
    """Create a dataset item and return its ID.

    Uses all fields from item_data (except 'id') to support flexible dataset schemas.
    """
    # Use all fields from item_data except 'id' (which is handled separately)
    new_item = {k: v for k, v in item_data.items() if k != "id" and v is not None}

    # Ensure item is not empty (backend requires non-empty data)
    if not new_item:
        raise ValueError(
            "Dataset item data is empty - at least one field must be provided"
        )

    dataset.insert([new_item])

    # Find the newly created item by matching all fields
    items = dataset.get_items()
    for item in items:
        # Compare all fields (excluding 'id')
        item_without_id = {k: v for k, v in item.items() if k != "id"}
        if item_without_id == new_item:
            item_id = item.get("id")
            if item_id is not None:
                return item_id

    dataset_name = getattr(dataset, "name", None)
    dataset_info = f", Dataset: {dataset_name!r}" if dataset_name else ""
    raise Exception(
        f"Failed to create dataset item. " f"Content: {new_item!r}{dataset_info}"
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


def clean_feedback_scores(
    feedback_scores: Optional[List[Dict[str, Any]]],
) -> Optional[List[Dict[str, Any]]]:
    """Clean feedback scores by removing fields that are not allowed when creating them.

    Exported feedback scores include read-only fields like 'source', 'created_at', etc.
    that must be removed before importing.

    Allowed fields: name, value, category_name, reason
    """
    if not feedback_scores:
        return None

    cleaned_scores = []
    for score in feedback_scores:
        if not isinstance(score, dict):
            continue

        # Only keep allowed fields
        cleaned_score = {
            "name": score.get("name"),
            "value": score.get("value"),
        }

        # Add optional fields if they exist
        if "category_name" in score:
            cleaned_score["category_name"] = score.get("category_name")
        if "reason" in score:
            cleaned_score["reason"] = score.get("reason")

        # Only add if name and value are present
        if cleaned_score.get("name") and cleaned_score.get("value") is not None:
            cleaned_scores.append(cleaned_score)

    return cleaned_scores if cleaned_scores else None
