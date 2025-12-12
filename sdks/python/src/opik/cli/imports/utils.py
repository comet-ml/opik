"""Common utilities for import functionality."""

from typing import Any, Dict, List, Optional

import opik
from opik.types import FeedbackScoreDict
from rich.console import Console
from rich.table import Table

console = Console()


def debug_print(message: str, debug: bool) -> None:
    """Print debug message only if debug is enabled."""
    if debug:
        console.print(f"[blue]{message}[/blue]")


def matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using case-insensitive substring matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


def translate_trace_id(
    original_trace_id: str, trace_id_map: Dict[str, str]
) -> Optional[str]:
    """Translate an original trace id from export to the newly created id.

    Args:
        original_trace_id: The original trace ID from the export
        trace_id_map: Mapping from original trace IDs to new trace IDs (required, can be empty)

    Returns:
        The new trace ID if found in the map, None otherwise.
    """
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
        return trace_id

    # Fall back to direct trace_id
    return item_data.get("trace_id")


def clean_feedback_scores(
    feedback_scores: Optional[List[Dict[str, Any]]],
) -> Optional[List[FeedbackScoreDict]]:
    """Clean feedback scores by removing fields that are not allowed when creating them.

    Exported feedback scores include read-only fields like 'source', 'created_at', etc.
    that must be removed before importing.

    Allowed fields: name, value, category_name, reason
    """
    if not feedback_scores:
        return None

    cleaned_scores: List[FeedbackScoreDict] = []
    for score in feedback_scores:
        if not isinstance(score, dict):
            continue

        # Only keep allowed fields
        name = score.get("name")
        value = score.get("value")

        # Only add if name and value are present
        if not name or value is None:
            continue

        # Construct FeedbackScoreDict with required fields
        cleaned_score: FeedbackScoreDict = {
            "name": name,
            "value": value,
        }

        # Add optional fields if they exist
        if "category_name" in score:
            cleaned_score["category_name"] = score.get("category_name")
        if "reason" in score:
            cleaned_score["reason"] = score.get("reason")

        cleaned_scores.append(cleaned_score)

    return cleaned_scores if cleaned_scores else None


def print_import_summary(stats: Dict[str, int]) -> None:
    """Print a nice summary table of import statistics."""
    table = Table(
        title="📥 Import Summary", show_header=True, header_style="bold magenta"
    )
    table.add_column("Type", style="cyan", no_wrap=True)
    table.add_column("Imported", justify="right", style="green")
    table.add_column("Skipped", justify="right", style="yellow")
    table.add_column("Errors", justify="right", style="red")

    # Add rows for each type
    if (
        stats.get("experiments", 0) > 0
        or stats.get("experiments_skipped", 0) > 0
        or stats.get("experiments_errors", 0) > 0
    ):
        imported = stats.get("experiments", 0)
        skipped = stats.get("experiments_skipped", 0)
        errors = stats.get("experiments_errors", 0)
        table.add_row(
            "🧪 Experiments",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("datasets", 0) > 0
        or stats.get("datasets_skipped", 0) > 0
        or stats.get("datasets_errors", 0) > 0
    ):
        imported = stats.get("datasets", 0)
        skipped = stats.get("datasets_skipped", 0)
        errors = stats.get("datasets_errors", 0)
        table.add_row(
            "📊 Datasets",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("traces", 0) > 0
        or stats.get("traces_skipped", 0) > 0
        or stats.get("traces_errors", 0) > 0
    ):
        imported = stats.get("traces", 0)
        skipped = stats.get("traces_skipped", 0)
        errors = stats.get("traces_errors", 0)
        table.add_row(
            "🔍 Traces",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("prompts", 0) > 0
        or stats.get("prompts_skipped", 0) > 0
        or stats.get("prompts_errors", 0) > 0
    ):
        imported = stats.get("prompts", 0)
        skipped = stats.get("prompts_skipped", 0)
        errors = stats.get("prompts_errors", 0)
        table.add_row(
            "💬 Prompts",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("projects", 0) > 0
        or stats.get("projects_skipped", 0) > 0
        or stats.get("projects_errors", 0) > 0
    ):
        imported = stats.get("projects", 0)
        skipped = stats.get("projects_skipped", 0)
        errors = stats.get("projects_errors", 0)
        table.add_row(
            "📁 Projects",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    # Calculate totals
    total_imported = sum(
        [
            stats.get(key, 0)
            for key in ["experiments", "datasets", "traces", "prompts", "projects"]
        ]
    )
    total_skipped = sum(
        [
            stats.get(key, 0)
            for key in [
                "experiments_skipped",
                "datasets_skipped",
                "traces_skipped",
                "prompts_skipped",
                "projects_skipped",
            ]
        ]
    )
    total_errors = sum(
        [
            stats.get(key, 0)
            for key in [
                "experiments_errors",
                "datasets_errors",
                "traces_errors",
                "prompts_errors",
                "projects_errors",
            ]
        ]
    )

    table.add_row("", "", "", "", style="bold")
    table.add_row(
        "📦 Total",
        str(total_imported),
        str(total_skipped) if total_skipped > 0 else "",
        str(total_errors) if total_errors > 0 else "",
        style="bold green",
    )

    console.print()
    console.print(table)
    console.print()
