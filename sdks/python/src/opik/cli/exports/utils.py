"""Common utilities for export functionality."""

import csv
import dataclasses
import json
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

from rich.console import Console
from rich.table import Table

import opik.dict_utils as dict_utils
from opik.api_objects.experiment.experiment_item import ExperimentItemContent

console = Console()


def matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using simple string matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


def serialize_experiment_item(item: ExperimentItemContent) -> Dict[str, Any]:
    """Serialize an ExperimentItemContent dataclass to a dictionary."""
    return dataclasses.asdict(item)


def should_skip_file(file_path: Path, force: bool) -> bool:
    """Check if a file should be skipped based on existence and force flag."""
    return file_path.exists() and not force


def write_csv_data(
    data: Dict[str, Any],
    file_path: Path,
    csv_row_converter_func: Callable[[Dict[str, Any]], List[Dict]],
) -> None:
    """Write data to CSV file using the provided row converter function."""
    try:
        csv_rows = csv_row_converter_func(data)
        if not csv_rows:
            raise ValueError(f"CSV row converter returned empty list for {file_path}")
        with open(file_path, "w", newline="", encoding="utf-8") as csv_file_handle:
            csv_fieldnames = list(csv_rows[0].keys())
            csv_writer = csv.DictWriter(csv_file_handle, fieldnames=csv_fieldnames)
            csv_writer.writeheader()
            csv_writer.writerows(csv_rows)
    except Exception as e:
        # Re-raise with more context
        raise RuntimeError(f"Failed to write CSV file {file_path}: {e}") from e


def write_json_data(data: Dict[str, Any], file_path: Path) -> None:
    """Write data to JSON file."""
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, default=str)


def debug_print(message: str, debug: bool) -> None:
    """Print debug message only if debug is enabled."""
    if debug:
        console.print(f"[blue]{message}[/blue]")


def create_experiment_data_structure(
    experiment: Any, experiment_items: List[ExperimentItemContent]
) -> Dict[str, Any]:
    """Create a comprehensive experiment data structure for export."""
    # Get the full experiment data which contains all fields
    experiment_data_obj = experiment.get_experiment_data()

    return {
        "experiment": {
            "id": experiment.id,
            "name": experiment.name,
            "dataset_name": experiment.dataset_name,
            "metadata": getattr(experiment_data_obj, "metadata", None),
            "type": getattr(experiment_data_obj, "type", None),
            "status": getattr(experiment_data_obj, "status", None),
            "created_at": getattr(experiment_data_obj, "created_at", None),
            "last_updated_at": getattr(experiment_data_obj, "last_updated_at", None),
            "created_by": getattr(experiment_data_obj, "created_by", None),
            "last_updated_by": getattr(experiment_data_obj, "last_updated_by", None),
            "trace_count": getattr(experiment_data_obj, "trace_count", None),
            "total_estimated_cost": getattr(
                experiment_data_obj, "total_estimated_cost", None
            ),
            "total_estimated_cost_avg": getattr(
                experiment_data_obj, "total_estimated_cost_avg", None
            ),
            "usage": getattr(experiment_data_obj, "usage", None),
            "feedback_scores": getattr(experiment_data_obj, "feedback_scores", None),
            "comments": getattr(experiment_data_obj, "comments", None),
            "duration": getattr(experiment_data_obj, "duration", None),
            "prompt_version": getattr(experiment_data_obj, "prompt_version", None),
            "prompt_versions": getattr(experiment_data_obj, "prompt_versions", None),
        },
        "items": [serialize_experiment_item(item) for item in experiment_items],
        "downloaded_at": datetime.now().isoformat(),
    }


def dump_to_file(
    data: dict,
    file_path: Path,
    file_format: str,
    csv_writer: Optional[csv.DictWriter] = None,
    csv_fieldnames: Optional[List[str]] = None,
    data_type: str = "trace",
) -> tuple:
    """
    Helper function to dump data to file in the specified format.

    Args:
        data: The data to dump
        file_path: Path where to save the file
        file_format: Format to use ("json" or "csv")
        csv_writer: Existing CSV writer (for CSV format)
        csv_fieldnames: Existing CSV fieldnames (for CSV format)
        data_type: Type of data ("trace", "dataset", "prompt", "experiment")

    Returns:
        Tuple of (csv_writer, csv_fieldnames) for CSV format, or (None, None) for JSON
    """
    if file_format.lower() == "csv":
        # Convert to CSV rows based on data type
        if data_type == "trace":
            csv_rows = trace_to_csv_rows(data)
        elif data_type == "dataset":
            csv_rows = dataset_to_csv_rows(data)
        elif data_type == "prompt":
            csv_rows = prompt_to_csv_rows(data)
        elif data_type == "experiment":
            csv_rows = experiment_to_csv_rows(data)
        else:
            # Fallback to trace format for unknown types
            csv_rows = trace_to_csv_rows(data)

        # Initialize CSV writer if not already done
        if csv_writer is None and csv_rows:
            # Use context manager to ensure file is properly closed
            with open(file_path, "w", newline="", encoding="utf-8") as csv_file_handle:
                csv_fieldnames = list(csv_rows[0].keys())
                csv_writer = csv.DictWriter(csv_file_handle, fieldnames=csv_fieldnames)
                csv_writer.writeheader()
                # Write rows while file is open
                csv_writer.writerows(csv_rows)

            # File is closed, return None, None
            return None, None

        # Write rows to existing writer (caller manages file lifecycle)
        if csv_writer and csv_rows:
            csv_writer.writerows(csv_rows)

        return csv_writer, csv_fieldnames
    else:
        # Save to JSON file
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, default=str)

        return None, None


def trace_to_csv_rows(trace_data: dict) -> List[Dict]:
    """Convert trace data to CSV rows format."""
    trace = trace_data["trace"]
    spans = trace_data.get("spans", [])

    # Flatten trace data with "trace" prefix
    trace_flat = dict_utils.flatten_dict(trace, parent_key="trace", delim="_")

    # If no spans, create a single row for the trace
    if not spans:
        # Create empty span fields to maintain consistent structure
        span_flat = {f"span_{key}": "" for key in trace.keys()}
        span_flat["span_parent_span_id"] = ""  # Special case for parent_span_id

        # Combine trace and empty span data
        row = {**trace_flat, **span_flat}
        return [row]

    # Create rows for each span
    rows = []
    for span in spans:
        # Flatten span data with "span" prefix
        span_flat = dict_utils.flatten_dict(span, parent_key="span", delim="_")

        # Combine trace and span data
        row = {**trace_flat, **span_flat}
        rows.append(row)

    return rows


def dataset_to_csv_rows(dataset_data: dict) -> List[Dict]:
    """Convert dataset data to CSV rows format."""
    rows = []

    # Create a row for each dataset item
    items = dataset_data.get("items", [])
    for i, item in enumerate(items):
        # Flatten item data - use all fields from the item
        # (datasets can have any user-defined keys/values)
        item_flat = dict_utils.flatten_dict(
            item,  # Use the entire item dict, not just hardcoded fields
            parent_key="item",
            delim="_",
        )

        # Create row with item data and index
        row = {**item_flat}
        row["item_index"] = i  # Add index for ordering
        rows.append(row)

    return rows


def prompt_to_csv_rows(prompt_data: dict) -> List[Dict]:
    """Convert prompt data to CSV rows format."""
    # Flatten prompt data
    prompt_flat = dict_utils.flatten_dict(prompt_data, parent_key="prompt", delim="_")

    # Create a single row for the prompt
    return [prompt_flat]


def experiment_to_csv_rows(experiment_data: dict) -> List[Dict]:
    """Convert experiment data to CSV rows format."""
    rows = []

    # Flatten experiment metadata
    experiment_flat = dict_utils.flatten_dict(
        {
            "id": experiment_data.get("experiment", {}).get("id"),
            "name": experiment_data.get("experiment", {}).get("name"),
            "dataset_name": experiment_data.get("experiment", {}).get("dataset_name"),
            "type": experiment_data.get("experiment", {}).get("type"),
            "status": experiment_data.get("experiment", {}).get("status"),
            "created_at": experiment_data.get("experiment", {}).get("created_at"),
            "last_updated_at": experiment_data.get("experiment", {}).get(
                "last_updated_at"
            ),
            "created_by": experiment_data.get("experiment", {}).get("created_by"),
            "last_updated_by": experiment_data.get("experiment", {}).get(
                "last_updated_by"
            ),
            "trace_count": experiment_data.get("experiment", {}).get("trace_count"),
            "total_estimated_cost": experiment_data.get("experiment", {}).get(
                "total_estimated_cost"
            ),
            "downloaded_at": experiment_data.get("downloaded_at"),
        },
        parent_key="experiment",
        delim="_",
    )

    # Create a row for each experiment item
    items = experiment_data.get("items", [])
    for i, item in enumerate(items):
        # Flatten item data
        item_flat = dict_utils.flatten_dict(
            {
                "id": item.get("id"),
                "experiment_id": item.get("experiment_id"),
                "dataset_item_id": item.get("dataset_item_id"),
                "trace_id": item.get("trace_id"),
                "input": item.get("input"),
                "output": item.get("output"),
                "feedback_scores": item.get("feedback_scores"),
                "comments": item.get("comments"),
                "total_estimated_cost": item.get("total_estimated_cost"),
                "duration": item.get("duration"),
                "usage": item.get("usage"),
                "created_at": item.get("created_at"),
                "last_updated_at": item.get("last_updated_at"),
                "created_by": item.get("created_by"),
                "last_updated_by": item.get("last_updated_by"),
                "trace_visibility_mode": item.get("trace_visibility_mode"),
            },
            parent_key="item",
            delim="_",
        )

        # Combine experiment and item data
        row = {**experiment_flat, **item_flat}
        row["item_index"] = i  # Add index for ordering
        rows.append(row)

    # If no items, return just the experiment metadata
    if not items:
        rows.append(experiment_flat)

    return rows


def print_export_summary(stats: Dict[str, int], format: str = "json") -> None:
    """Print a nice summary table of export statistics."""
    table = Table(
        title="📊 Export Summary", show_header=True, header_style="bold magenta"
    )
    table.add_column("Type", style="cyan", no_wrap=True)
    table.add_column("Exported", justify="right", style="green")
    table.add_column("Skipped", justify="right", style="yellow")
    table.add_column("Files", style="blue")

    # Add rows for each type
    if stats.get("experiments", 0) > 0 or stats.get("experiments_skipped", 0) > 0:
        exported = stats.get("experiments", 0)
        skipped = stats.get("experiments_skipped", 0)
        experiment_file_pattern = (
            "experiments_*.csv" if format.lower() == "csv" else "experiment_*.json"
        )
        table.add_row(
            "🧪 Experiments",
            str(exported),
            str(skipped) if skipped > 0 else "",
            experiment_file_pattern,
        )

    if stats.get("datasets", 0) > 0 or stats.get("datasets_skipped", 0) > 0:
        exported = stats.get("datasets", 0)
        skipped = stats.get("datasets_skipped", 0)
        dataset_file_pattern = (
            "dataset_*.csv" if format.lower() == "csv" else "dataset_*.json"
        )
        table.add_row(
            "📊 Datasets",
            str(exported),
            str(skipped) if skipped > 0 else "",
            dataset_file_pattern,
        )

    if stats.get("traces", 0) > 0 or stats.get("traces_skipped", 0) > 0:
        exported = stats.get("traces", 0)
        skipped = stats.get("traces_skipped", 0)
        trace_file_pattern = (
            "trace_*.csv" if format.lower() == "csv" else "trace_*.json"
        )
        table.add_row(
            "🔍 Traces",
            str(exported),
            str(skipped) if skipped > 0 else "",
            trace_file_pattern,
        )

    if stats.get("prompts", 0) > 0 or stats.get("prompts_skipped", 0) > 0:
        exported = stats.get("prompts", 0)
        skipped = stats.get("prompts_skipped", 0)
        prompt_file_pattern = (
            "prompts_*.csv" if format.lower() == "csv" else "prompt_*.json"
        )
        table.add_row(
            "💬 Prompts",
            str(exported),
            str(skipped) if skipped > 0 else "",
            prompt_file_pattern,
        )

    if stats.get("projects", 0) > 0 or stats.get("projects_skipped", 0) > 0:
        exported = stats.get("projects", 0)
        skipped = stats.get("projects_skipped", 0)
        table.add_row(
            "📁 Projects",
            str(exported),
            str(skipped) if skipped > 0 else "",
            "project directories",
        )

    # Calculate totals
    total_exported = sum(
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
    total_files = total_exported + total_skipped

    table.add_row("", "", "", "", style="bold")
    table.add_row(
        "📦 Total",
        str(total_exported),
        str(total_skipped) if total_skipped > 0 else "",
        f"{total_files} files",
        style="bold green",
    )

    console.print()
    console.print(table)
    console.print()
