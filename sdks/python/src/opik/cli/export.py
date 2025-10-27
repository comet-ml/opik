"""Download command for Opik CLI."""

import csv
import json
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik
from opik.api_objects.dataset.dataset import Dataset
from opik.api_objects.experiment import Experiment
from opik.api_objects.prompt.prompt import Prompt
from opik.rest_api.types.project_public import ProjectPublic

console = Console()

# Constants for export command
FALLBACK_PAGE_SIZE = 10  # Smaller page size for error recovery
MAX_EXPERIMENT_ITEMS_PER_FETCH = 1000  # Maximum items to fetch per experiment


def _matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using simple string matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


def _is_experiment_id(pattern: str) -> bool:
    """Check if the pattern looks like an experiment ID (UUID format)."""
    # Simple UUID format check: 8-4-4-4-12 hexadecimal digits with dashes
    if len(pattern) != 36:
        return False
    if pattern.count("-") != 4:
        return False
    # Check that all characters are hex digits or dashes
    for char in pattern:
        if char not in "0123456789abcdefABCDEF-":
            return False
    return True


def _serialize_experiment_item(item: Any) -> Dict[str, Any]:
    """Safely serialize an experiment item to a dictionary."""
    try:
        # Try model_dump() first (Pydantic v2)
        if hasattr(item, "model_dump"):
            return item.model_dump()
    except Exception:
        pass

    try:
        # Try dict() method (Pydantic v1)
        if hasattr(item, "dict"):
            return item.dict()
    except Exception:
        pass

    # Fallback: manually extract attributes
    result = {}
    for attr in [
        "id",
        "experiment_id",
        "dataset_item_id",
        "trace_id",
        "input",
        "output",
        "feedback_scores",
        "comments",
        "total_estimated_cost",
        "duration",
        "usage",
        "created_at",
        "last_updated_at",
        "created_by",
        "last_updated_by",
        "trace_visibility_mode",
    ]:
        if hasattr(item, attr):
            value = getattr(item, attr)
            if value is not None:
                # Handle datetime objects
                if hasattr(value, "isoformat"):
                    result[attr] = value.isoformat()
                # Handle lists of objects
                elif isinstance(value, list):
                    result[attr] = [
                        _serialize_experiment_item(v) if hasattr(v, "__dict__") else v
                        for v in value
                    ]
                # Handle other objects
                elif hasattr(value, "__dict__"):
                    result[attr] = _serialize_experiment_item(value)
                else:
                    result[attr] = value
    return result


def _export_experiment_prompts(
    client: opik.Opik,
    experiment: Any,
    output_dir: Path,
    force: bool,
    debug: bool,
    format: str = "json",
) -> int:
    """Export prompts referenced by an experiment."""
    try:
        if not experiment.prompt_versions:
            return 0

        prompts_dir = output_dir.parent / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)

        exported_count = 0
        for prompt_version in experiment.prompt_versions:
            try:
                _debug_print(f"Exporting prompt: {prompt_version.prompt_id}", debug)

                # Get the prompt
                prompt = client.get_prompt(prompt_version.prompt_id)
                if not prompt:
                    if debug:
                        console.print(
                            f"[yellow]Warning: Prompt {prompt_version.prompt_id} not found[/yellow]"
                        )
                    continue

                # Get prompt history
                prompt_history = client.get_prompt_history(prompt_version.prompt_id)

                # Create prompt data structure
                prompt_data = {
                    "prompt": {
                        "id": getattr(prompt, "id", None),
                        "name": prompt.name,
                        "description": getattr(prompt, "description", None),
                        "created_at": (
                            created_at.isoformat()
                            if (created_at := getattr(prompt, "created_at", None))
                            else None
                        ),
                        "last_updated_at": (
                            last_updated_at.isoformat()
                            if (
                                last_updated_at := getattr(
                                    prompt, "last_updated_at", None
                                )
                            )
                            else None
                        ),
                    },
                    "current_version": {
                        "prompt": prompt.prompt,
                        "metadata": prompt.metadata,
                        "type": prompt.type if prompt.type else None,
                        "commit": prompt.commit,
                    },
                    "history": [
                        {
                            "prompt": version.prompt,
                            "metadata": version.metadata,
                            "type": version.type if version.type else None,
                            "commit": version.commit,
                        }
                        for version in prompt_history
                    ],
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save prompt data using the appropriate format
                if format.lower() == "csv":
                    prompt_file = (
                        prompts_dir
                        / f"prompts_{prompt.name or getattr(prompt, 'id', 'unknown')}.csv"
                    )
                else:
                    prompt_file = (
                        prompts_dir
                        / f"prompt_{prompt.name or getattr(prompt, 'id', 'unknown')}.json"
                    )
                if not prompt_file.exists() or force:
                    if format.lower() == "csv":
                        _write_csv_data(prompt_data, prompt_file, _prompt_to_csv_rows)
                    else:
                        _write_json_data(prompt_data, prompt_file)

                    if debug:
                        console.print(
                            f"[green]Exported prompt: {prompt.name or getattr(prompt, 'id', 'unknown')}[/green]"
                        )
                    exported_count += 1
                else:
                    _debug_print(
                        f"Skipping prompt {prompt.name} (already exists)", debug
                    )
                    exported_count += 1  # Count as exported even if skipped

            except Exception as e:
                if debug:
                    console.print(
                        f"[yellow]Warning: Could not export prompt {prompt_version.prompt_id}: {e}[/yellow]"
                    )
                continue

        return exported_count

    except Exception as e:
        if debug:
            console.print(
                f"[yellow]Warning: Could not export experiment prompts: {e}[/yellow]"
            )
        return 0


def _print_export_summary(stats: Dict[str, int], format: str = "json") -> None:
    """Print a nice summary table of export statistics."""
    from rich.table import Table

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
            "datasets_*.csv" if format.lower() == "csv" else "dataset_*.json"
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
            "traces_*.csv" if format.lower() == "csv" else "trace_*.json"
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


def _export_related_prompts_by_name(
    client: opik.Opik,
    experiment: Any,
    output_dir: Path,
    force: bool,
    debug: bool,
    format: str = "json",
) -> int:
    """Export prompts that might be related to the experiment by name or content."""
    try:
        prompts_dir = output_dir.parent / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)

        # Get all prompts in the workspace
        all_prompts = client.search_prompts()
        _debug_print(f"Found {len(all_prompts)} total prompts in workspace", debug)

        # Look for prompts that might be related to this experiment
        related_prompts = []
        experiment_name = experiment.name or ""
        experiment_id = experiment.id or ""

        for prompt in all_prompts:
            prompt_name = prompt.name.lower()
            is_related = False

            # Check if prompt name contains experiment keywords
            if any(
                keyword in prompt_name
                for keyword in ["mcp", "evaluation", "experiment"]
            ):
                is_related = True
            elif any(
                keyword in prompt_name for keyword in experiment_name.lower().split("-")
            ):
                is_related = True
            elif any(
                keyword in prompt_name for keyword in experiment_id.lower().split("-")
            ):
                is_related = True

            if is_related:
                related_prompts.append(prompt)
                if debug:
                    console.print(
                        f"[blue]Found potentially related prompt: {prompt.name}[/blue]"
                    )

        if not related_prompts:
            _debug_print("No related prompts found by name matching", debug)
            return 0

        console.print(
            f"[blue]Exporting {len(related_prompts)} potentially related prompts...[/blue]"
        )

        exported_count = 0
        # Export each related prompt
        for prompt in related_prompts:
            try:
                _debug_print(f"Exporting related prompt: {prompt.name}", debug)

                # Get prompt history
                prompt_history = client.get_prompt_history(prompt.name)

                # Create prompt data structure
                prompt_data = {
                    "prompt": {
                        "id": getattr(prompt, "__internal_api__prompt_id__", None),
                        "name": prompt.name,
                        "description": getattr(prompt, "description", None),
                        "created_at": getattr(prompt, "created_at", None),
                        "last_updated_at": getattr(prompt, "last_updated_at", None),
                    },
                    "current_version": {
                        "prompt": prompt.prompt,
                        "metadata": prompt.metadata,
                        "type": prompt.type if prompt.type else None,
                        "commit": prompt.commit,
                    },
                    "history": [
                        {
                            "prompt": version.prompt,
                            "metadata": version.metadata,
                            "type": version.type if version.type else None,
                            "commit": version.commit,
                        }
                        for version in prompt_history
                    ],
                    "downloaded_at": datetime.now().isoformat(),
                    "related_to_experiment": experiment.name or experiment.id,
                }

                # Save prompt data using the appropriate format
                if format.lower() == "csv":
                    prompt_file = prompts_dir / f"prompts_{prompt.name}.csv"
                else:
                    prompt_file = prompts_dir / f"prompt_{prompt.name}.json"
                if not prompt_file.exists() or force:
                    if format.lower() == "csv":
                        _write_csv_data(prompt_data, prompt_file, _prompt_to_csv_rows)
                    else:
                        _write_json_data(prompt_data, prompt_file)

                    console.print(
                        f"[green]Exported related prompt: {prompt.name}[/green]"
                    )
                    exported_count += 1
                else:
                    _debug_print(
                        f"Skipping prompt {prompt.name} (already exists)", debug
                    )
                    exported_count += 1  # Count as exported even if skipped

            except Exception as e:
                if debug:
                    console.print(
                        f"[yellow]Warning: Could not export related prompt {prompt.name}: {e}[/yellow]"
                    )
                continue

        return exported_count

    except Exception as e:
        if debug:
            console.print(
                f"[yellow]Warning: Could not export related prompts: {e}[/yellow]"
            )
        return 0


def _flatten_dict_with_prefix(data: Dict, prefix: str = "") -> Dict:
    """Flatten a nested dictionary with a prefix for CSV export."""
    if not data:
        return {}

    flattened = {}
    for key, value in data.items():
        prefixed_key = f"{prefix}_{key}" if prefix else key
        if isinstance(value, (dict, list)):
            flattened[prefixed_key] = str(value)
        else:
            flattened[prefixed_key] = value if value is not None else ""

    return flattened


def _should_skip_file(file_path: Path, force: bool) -> bool:
    """Check if a file should be skipped based on existence and force flag."""
    return file_path.exists() and not force


def _write_csv_data(
    data: Dict[str, Any],
    file_path: Path,
    csv_row_converter_func: Callable[[Dict[str, Any]], List[Dict]],
) -> None:
    """Write data to CSV file using the provided row converter function."""
    csv_rows = csv_row_converter_func(data)
    if csv_rows:
        with open(file_path, "w", newline="", encoding="utf-8") as csv_file_handle:
            csv_fieldnames = list(csv_rows[0].keys())
            csv_writer = csv.DictWriter(csv_file_handle, fieldnames=csv_fieldnames)
            csv_writer.writeheader()
            csv_writer.writerows(csv_rows)


def _write_json_data(data: Dict[str, Any], file_path: Path) -> None:
    """Write data to JSON file."""
    with open(file_path, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=2, default=str)


def _debug_print(message: str, debug: bool) -> None:
    """Print debug message only if debug is enabled."""
    if debug:
        console.print(f"[blue]{message}[/blue]")


def _create_experiment_data_structure(
    experiment: Any, experiment_items: List[Any]
) -> Dict[str, Any]:
    """Create a comprehensive experiment data structure for export."""
    return {
        "experiment": {
            "id": experiment.id,
            "name": experiment.name,
            "dataset_name": experiment.dataset_name,
            "metadata": experiment.metadata,
            "type": experiment.type,
            "status": experiment.status,
            "created_at": experiment.created_at,
            "last_updated_at": experiment.last_updated_at,
            "created_by": experiment.created_by,
            "last_updated_by": experiment.last_updated_by,
            "trace_count": experiment.trace_count,
            "total_estimated_cost": experiment.total_estimated_cost,
            "total_estimated_cost_avg": experiment.total_estimated_cost_avg,
            "usage": experiment.usage,
            "feedback_scores": experiment.feedback_scores,
            "comments": experiment.comments,
            "duration": experiment.duration,
            "prompt_version": experiment.prompt_version,
            "prompt_versions": experiment.prompt_versions,
        },
        "items": [_serialize_experiment_item(item) for item in experiment_items],
        "downloaded_at": datetime.now().isoformat(),
    }


def _export_experiment_datasets(
    client: opik.Opik,
    datasets_to_export: set,
    datasets_dir: Path,
    format: str,
    debug: bool,
) -> int:
    """Export datasets related to an experiment."""
    exported_count = 0
    for dataset_name in datasets_to_export:
        try:
            dataset_obj = Dataset(
                name=dataset_name,
                description=None,  # Description not available from experiment
                rest_client=client.rest_client,
            )
            dataset_items = dataset_obj.get_items()

            dataset_data = {
                "dataset": {
                    "name": dataset_name,
                    "id": getattr(dataset_obj, "id", None),
                },
                "items": [_serialize_experiment_item(item) for item in dataset_items],
                "downloaded_at": datetime.now().isoformat(),
            }

            # Use format parameter to determine file extension and save method
            if format.lower() == "csv":
                dataset_file = datasets_dir / f"datasets_{dataset_name}.csv"
            else:
                dataset_file = datasets_dir / f"dataset_{dataset_name}.json"
            datasets_dir.mkdir(parents=True, exist_ok=True)

            # Save to file using the appropriate format
            if format.lower() == "csv":
                _write_csv_data(dataset_data, dataset_file, _dataset_to_csv_rows)
            else:
                _write_json_data(dataset_data, dataset_file)

            console.print(f"[green]Exported dataset: {dataset_name}[/green]")
            exported_count += 1
        except Exception as e:
            if debug:
                console.print(
                    f"[yellow]Warning: Could not export dataset {dataset_name}: {e}[/yellow]"
                )
    return exported_count


def _dump_to_file(
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
            csv_rows = _trace_to_csv_rows(data)
        elif data_type == "dataset":
            csv_rows = _dataset_to_csv_rows(data)
        elif data_type == "prompt":
            csv_rows = _prompt_to_csv_rows(data)
        elif data_type == "experiment":
            csv_rows = _experiment_to_csv_rows(data)
        else:
            # Fallback to trace format for unknown types
            csv_rows = _trace_to_csv_rows(data)

        # Initialize CSV writer if not already done
        if csv_writer is None and csv_rows:
            csv_file_handle = open(file_path, "w", newline="", encoding="utf-8")
            csv_fieldnames = list(csv_rows[0].keys())
            csv_writer = csv.DictWriter(csv_file_handle, fieldnames=csv_fieldnames)
            csv_writer.writeheader()

        # Write rows
        if csv_writer and csv_rows:
            csv_writer.writerows(csv_rows)

        return csv_writer, csv_fieldnames
    else:
        # Save to JSON file
        with open(file_path, "w", encoding="utf-8") as f:
            json.dump(data, f, indent=2, default=str)

        return None, None


def _trace_to_csv_rows(trace_data: dict) -> List[Dict]:
    """Convert trace data to CSV rows format."""
    trace = trace_data["trace"]
    spans = trace_data.get("spans", [])

    # Flatten trace data with "trace" prefix
    trace_flat = _flatten_dict_with_prefix(trace, "trace")

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
        span_flat = _flatten_dict_with_prefix(span, "span")

        # Combine trace and span data
        row = {**trace_flat, **span_flat}
        rows.append(row)

    return rows


def _dataset_to_csv_rows(dataset_data: dict) -> List[Dict]:
    """Convert dataset data to CSV rows format."""
    rows = []

    # Flatten dataset metadata
    dataset_flat = _flatten_dict_with_prefix(
        {
            "name": dataset_data.get("name"),
            "description": dataset_data.get("description"),
            "downloaded_at": dataset_data.get("downloaded_at"),
        },
        "dataset",
    )

    # Create a row for each dataset item
    items = dataset_data.get("items", [])
    for i, item in enumerate(items):
        # Flatten item data
        item_flat = _flatten_dict_with_prefix(
            {
                "input": item.get("input"),
                "expected_output": item.get("expected_output"),
                "metadata": item.get("metadata"),
            },
            "item",
        )

        # Combine dataset and item data
        row = {**dataset_flat, **item_flat}
        row["item_index"] = i  # Add index for ordering
        rows.append(row)

    return rows


def _prompt_to_csv_rows(prompt_data: dict) -> List[Dict]:
    """Convert prompt data to CSV rows format."""
    # Flatten prompt data
    prompt_flat = _flatten_dict_with_prefix(prompt_data, "prompt")

    # Create a single row for the prompt
    return [prompt_flat]


def _experiment_to_csv_rows(experiment_data: dict) -> List[Dict]:
    """Convert experiment data to CSV rows format."""
    rows = []

    # Flatten experiment metadata
    experiment_flat = _flatten_dict_with_prefix(
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
        "experiment",
    )

    # Create a row for each experiment item
    items = experiment_data.get("items", [])
    for i, item in enumerate(items):
        # Flatten item data
        item_flat = _flatten_dict_with_prefix(
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
            "item",
        )

        # Combine experiment and item data
        row = {**experiment_flat, **item_flat}
        row["item_index"] = i  # Add index for ordering
        rows.append(row)

    # If no items, return just the experiment metadata
    if not items:
        rows.append(experiment_flat)

    return rows


def _export_data_type(
    data_type: str,
    client: opik.Opik,
    output_dir: Path,
    max_results: int,
    name_pattern: Optional[str] = None,
    debug: bool = False,
    force: bool = False,
) -> int:
    """
    Helper function to export a specific data type.

    Args:
        data_type: Type of data to export ("traces", "datasets", "prompts", "experiments")
        client: Opik client instance
        output_dir: Directory to save exported data
        max_results: Maximum number of items to export
        name_pattern: Optional name pattern filter
        debug: Whether to enable debug output
        force: Whether to re-download items even if they already exist locally

    Returns:
        Number of items exported
    """
    if data_type == "traces":
        # This requires additional parameters, so we'll handle it separately
        raise ValueError(
            "Traces export requires additional parameters - use _export_traces directly"
        )
    elif data_type == "datasets":
        if debug:
            console.print("[blue]Downloading datasets...[/blue]")
        return _export_datasets(client, output_dir, max_results, name_pattern, debug)
    elif data_type == "prompts":
        if debug:
            console.print("[blue]Downloading prompts...[/blue]")
        return _export_prompts(client, output_dir, max_results, name_pattern, debug)
    elif data_type == "experiments":
        if debug:
            console.print("[blue]Downloading experiments...[/blue]")
        return _export_experiments(
            client, output_dir, max_results, name_pattern, debug, True, force
        )
    else:
        console.print(f"[red]Unknown data type: {data_type}[/red]")
        return 0


def _export_traces(
    client: opik.Opik,
    project_name: str,
    project_dir: Path,
    max_results: int,
    filter_string: Optional[str],
    name_pattern: Optional[str] = None,
    format: str = "json",
    debug: bool = False,
    force: bool = False,
) -> tuple[int, int]:
    """Download traces and their spans with pagination support for large projects."""
    if debug:
        console.print(
            f"[blue]DEBUG: _export_traces called with project_name: {project_name}, project_dir: {project_dir}[/blue]"
        )
    exported_count = 0
    skipped_count = 0
    page_size = min(100, max_results)  # Process in smaller batches
    current_page = 1
    total_processed = 0

    # For CSV format, set up streaming writer
    csv_file = None
    csv_file_handle = None
    csv_writer = None
    csv_fieldnames = None

    try:
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            console=console,
        ) as progress:
            task = progress.add_task("Searching for traces...", total=None)

            while total_processed < max_results:
                # Calculate how many traces to fetch in this batch
                remaining = max_results - total_processed
                current_page_size = min(page_size, remaining)

                try:
                    if debug:
                        console.print(
                            f"[blue]DEBUG: Getting traces by project with project_name: {project_name}, filter: {filter_string}, page: {current_page}, size: {current_page_size}[/blue]"
                        )
                    # Use get_traces_by_project for better performance when we know the project name
                    trace_page = client.rest_client.traces.get_traces_by_project(
                        project_name=project_name,
                        filters=filter_string,
                        page=current_page,
                        size=current_page_size,
                        truncate=False,  # Don't truncate data for download
                    )
                    traces = trace_page.content or []
                    if debug:
                        console.print(
                            f"[blue]DEBUG: Found {len(traces)} traces in project (page {current_page})[/blue]"
                        )
                except Exception as e:
                    # Check if it's an OQL parsing error and provide user-friendly message
                    error_msg = str(e)
                    if "Invalid value" in error_msg and (
                        "expected an string in double quotes" in error_msg
                        or "expected a string in double quotes" in error_msg
                    ):
                        console.print(
                            "[red]Error: Invalid filter format in export query.[/red]"
                        )
                        console.print(
                            "[yellow]This appears to be an internal query parsing issue. Please try the export again.[/yellow]"
                        )
                        if debug:
                            console.print(f"[blue]Technical details: {e}[/blue]")
                    else:
                        console.print(f"[red]Error searching traces: {e}[/red]")
                    break

                if not traces:
                    # No more traces to process
                    break

                # Update progress description to show current page
                progress.update(
                    task,
                    description=f"Downloading traces... (page {current_page}, {len(traces)} traces)",
                )

                # Filter traces by name pattern if specified
                if name_pattern:
                    original_count = len(traces)
                    traces = [
                        trace
                        for trace in traces
                        if _matches_name_pattern(trace.name or "", name_pattern)
                    ]
                    if len(traces) < original_count:
                        console.print(
                            f"[blue]Filtered to {len(traces)} traces matching pattern '{name_pattern}' in current batch[/blue]"
                        )

                if not traces:
                    # No traces match the name pattern, but we might have more to process
                    # Use original_traces for pagination, not the filtered empty list
                    total_processed += current_page_size
                    continue

                # Update progress for downloading
                progress.update(
                    task,
                    description=f"Downloading traces... (batch {total_processed // page_size + 1})",
                )

                # Download each trace with its spans
                for trace in traces:
                    try:
                        # Get spans for this trace
                        spans = client.search_spans(
                            project_name=project_name,
                            trace_id=trace.id,
                            max_results=1000,  # Get all spans for the trace
                            truncate=False,
                        )

                        # Create trace data structure
                        trace_data = {
                            "trace": trace.model_dump(),
                            "spans": [span.model_dump() for span in spans],
                            "downloaded_at": datetime.now().isoformat(),
                            "project_name": project_name,
                        }

                        # For CSV format, we need to handle this differently
                        # CSV creates a single consolidated file, not individual files
                        if format.lower() == "csv":
                            # For CSV, we only check the consolidated file once
                            csv_file_path = project_dir / f"traces_{project_name}.csv"
                            if _should_skip_file(csv_file_path, force):
                                if debug:
                                    console.print(
                                        f"[blue]Skipping CSV export (traces_{project_name}.csv already exists)[/blue]"
                                    )
                                # For CSV, if the file exists, we skip the entire export
                                return (
                                    0,
                                    1,
                                )  # 0 exported, 1 skipped (the consolidated file)
                            file_path = csv_file_path
                        else:
                            # For JSON, check each individual trace file
                            file_path = project_dir / f"trace_{trace.id}.json"
                            if _should_skip_file(file_path, force):
                                if debug:
                                    console.print(
                                        f"[blue]Skipping trace {trace.id} (already exists)[/blue]"
                                    )
                                skipped_count += 1
                                total_processed += 1
                                continue

                        # Use helper function to dump data
                        csv_writer, csv_fieldnames = _dump_to_file(
                            trace_data,
                            file_path,
                            format,
                            csv_writer,
                            csv_fieldnames,
                            "trace",
                        )

                        exported_count += 1
                        total_processed += 1

                    except Exception as e:
                        console.print(
                            f"[red]Error exporting trace {trace.id}: {e}[/red]"
                        )
                        continue

                # Update pagination for next iteration
                if traces:
                    current_page += 1
                else:
                    # No more traces to process
                    break

                # If we got fewer traces than requested, we've reached the end
                if len(traces) < current_page_size:
                    break

            # Final progress update
            if exported_count == 0:
                console.print("[yellow]No traces found in the project.[/yellow]")
            else:
                progress.update(
                    task, description=f"Exported {exported_count} traces total"
                )

    finally:
        # Close CSV file if it was opened
        if csv_file_handle:
            csv_file_handle.close()
        if csv_file and csv_file.exists():
            console.print(f"[green]CSV file saved to {csv_file}[/green]")

    return exported_count, skipped_count


def _export_specific_traces(
    client: opik.Opik,
    project_name: str,
    project_dir: Path,
    trace_ids: List[str],
    format: str = "json",
    debug: bool = False,
    force: bool = False,
) -> tuple[int, int]:
    """Export specific traces by their IDs."""
    if debug:
        console.print(
            f"[blue]DEBUG: _export_specific_traces called with {len(trace_ids)} trace IDs[/blue]"
        )

    exported_count = 0
    skipped_count = 0

    # For CSV format, set up streaming writer
    csv_file = None
    csv_file_handle = None
    csv_writer = None
    csv_fieldnames = None

    try:
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            console=console,
        ) as progress:
            task = progress.add_task(
                "Downloading specific traces...", total=len(trace_ids)
            )

            for i, trace_id in enumerate(trace_ids):
                try:
                    # Update progress
                    progress.update(
                        task,
                        description=f"Downloading trace {i+1}/{len(trace_ids)}: {trace_id[:8]}...",
                    )

                    # Get the specific trace by ID
                    try:
                        trace = client.get_trace_content(trace_id)
                    except Exception as e:
                        if debug:
                            console.print(
                                f"[yellow]Warning: Trace {trace_id} not found: {e}[/yellow]"
                            )
                        continue

                    # Get spans for this trace
                    spans = client.search_spans(
                        project_name=project_name,
                        trace_id=trace_id,
                        max_results=1000,
                        truncate=False,
                    )

                    # Create trace data structure
                    trace_data = {
                        "trace": trace.model_dump(),
                        "spans": [span.model_dump() for span in spans],
                        "downloaded_at": datetime.now().isoformat(),
                        "project_name": project_name,
                    }

                    # Determine file path based on format
                    if format.lower() == "csv":
                        file_path = project_dir / f"traces_{project_name}.csv"
                    else:
                        file_path = project_dir / f"trace_{trace_id}.json"

                    # Check if file already exists and force is not set
                    if _should_skip_file(file_path, force):
                        if debug:
                            console.print(
                                f"[blue]Skipping trace {trace_id} (already exists)[/blue]"
                            )
                        skipped_count += 1
                        progress.update(task, advance=1)
                        continue

                    # Use helper function to dump data
                    csv_writer, csv_fieldnames = _dump_to_file(
                        trace_data,
                        file_path,
                        format,
                        csv_writer,
                        csv_fieldnames,
                        "trace",
                    )

                    exported_count += 1

                except Exception as e:
                    if debug:
                        console.print(
                            f"[yellow]Warning: Could not export trace {trace_id}: {e}[/yellow]"
                        )
                    continue

                # Update progress
                progress.update(task, advance=1)

    finally:
        # Clean up CSV file handle
        if csv_file_handle:
            csv_file_handle.close()
        if csv_file and csv_file.exists():
            console.print(f"[green]CSV file saved to {csv_file}[/green]")

    return exported_count, skipped_count


def _export_datasets(
    client: opik.Opik,
    project_dir: Path,
    max_results: int,
    name_pattern: Optional[str] = None,
    debug: bool = False,
    format: str = "json",
) -> int:
    """Export datasets."""
    try:
        # For exact name matches, use direct lookup
        if name_pattern:
            try:
                dataset = client.get_dataset(name_pattern)
                datasets = [dataset]
                if debug:
                    console.print(
                        f"[blue]Found dataset by direct lookup: {dataset.name}[/blue]"
                    )
            except Exception as e:
                console.print(f"[red]Dataset '{name_pattern}' not found: {e}[/red]")
                sys.exit(1)
        else:
            console.print("[red]Dataset name is required[/red]")
            sys.exit(1)

        if not datasets:
            console.print(f"[red]Dataset '{name_pattern}' not found[/red]")
            sys.exit(1)

        exported_count = 0
        for dataset in datasets:
            try:
                # Get dataset items using the get_items method
                _debug_print(f"Getting items for dataset: {dataset.name}", debug)
                dataset_items = dataset.get_items()

                # Convert dataset items to the expected format for import
                formatted_items = []
                for item in dataset_items:
                    formatted_item = {
                        "input": item.get("input"),
                        "expected_output": item.get("expected_output"),
                        "metadata": item.get("metadata"),
                    }
                    formatted_items.append(formatted_item)

                # Create dataset data structure
                dataset_data = {
                    "name": dataset.name,
                    "description": dataset.description,
                    "items": formatted_items,
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save to file using the appropriate format
                if format.lower() == "csv":
                    dataset_file = project_dir / f"datasets_{dataset.name}.csv"
                    _write_csv_data(dataset_data, dataset_file, _dataset_to_csv_rows)
                else:
                    dataset_file = project_dir / f"dataset_{dataset.name}.json"
                    _write_json_data(dataset_data, dataset_file)

                exported_count += 1

            except Exception as e:
                console.print(
                    f"[red]Error downloading dataset {dataset.name}: {e}[/red]"
                )
                continue

        return exported_count

    except Exception as e:
        console.print(f"[red]Error exporting datasets: {e}[/red]")
        return 0


def _export_prompts(
    client: opik.Opik,
    project_dir: Path,
    max_results: int,
    name_pattern: Optional[str] = None,
    debug: bool = False,
    format: str = "json",
) -> int:
    """Export prompts."""
    try:
        # For exact name matches, use direct lookup
        if name_pattern:
            try:
                prompt = client.get_prompt(name_pattern)
                if prompt:
                    prompts = [prompt]
                    if debug:
                        console.print(
                            f"[blue]Found prompt by direct lookup: {prompt.name}[/blue]"
                        )
                else:
                    console.print(f"[red]Prompt '{name_pattern}' not found[/red]")
                    sys.exit(1)
            except Exception as e:
                console.print(f"[red]Prompt '{name_pattern}' not found: {e}[/red]")
                sys.exit(1)
        else:
            console.print("[red]Prompt name is required[/red]")
            sys.exit(1)

        if not prompts:
            console.print(f"[red]Prompt '{name_pattern}' not found[/red]")
            sys.exit(1)

        exported_count = 0
        for prompt in prompts:
            try:
                # Get prompt history
                prompt_history = client.get_prompt_history(prompt.name)

                # Create prompt data structure
                prompt_data = {
                    "name": prompt.name,
                    "current_version": {
                        "prompt": prompt.prompt
                        if isinstance(prompt, opik.Prompt)
                        else None,  # TODO: add support for chat prompts
                        "metadata": prompt.metadata,
                        "type": prompt.type if prompt.type else None,
                        "commit": prompt.commit,
                    },
                    "history": [
                        {
                            "prompt": version.prompt
                            if isinstance(version, opik.Prompt)
                            else None,  # TODO: add support for chat prompts
                            "metadata": version.metadata,
                            "type": version.type if version.type else None,
                            "commit": version.commit,
                        }
                        for version in prompt_history
                    ],
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save to file using the appropriate format
                if format.lower() == "csv":
                    prompt_file = (
                        project_dir / f"prompts_{prompt.name.replace('/', '_')}.csv"
                    )
                    _write_csv_data(prompt_data, prompt_file, _prompt_to_csv_rows)
                else:
                    prompt_file = (
                        project_dir / f"prompt_{prompt.name.replace('/', '_')}.json"
                    )
                    _write_json_data(prompt_data, prompt_file)

                exported_count += 1

            except Exception as e:
                console.print(f"[red]Error downloading prompt {prompt.name}: {e}[/red]")
                continue

        return exported_count

    except Exception as e:
        console.print(f"[red]Error exporting prompts: {e}[/red]")
        return 0


def _export_specific_experiment_by_id(
    client: opik.Opik,
    output_dir: Path,
    experiment_id: str,
    dataset: Optional[str],
    max_traces: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a specific experiment by ID, including related datasets and traces."""
    try:
        console.print(f"[blue]Fetching experiment by ID: {experiment_id}[/blue]")

        # Get the specific experiment by ID
        try:
            experiment = client.rest_client.experiments.get_experiment_by_id(
                experiment_id
            )
        except Exception as e:
            console.print(f"[red]Error fetching experiment {experiment_id}: {e}[/red]")
            return 0

        # Check dataset filter if provided
        if (
            dataset
            and hasattr(experiment, "dataset_name")
            and experiment.dataset_name != dataset
        ):
            console.print(
                f"[red]Experiment {experiment_id} is in dataset '{experiment.dataset_name}', not '{dataset}'[/red]"
            )
            return 0

        console.print(
            f"[green]Found experiment: {experiment.name or experiment.id}[/green]"
        )

        # Create directories for related data
        workspace_dir = output_dir.parent
        datasets_dir = workspace_dir / "datasets"
        projects_dir = workspace_dir / "projects"

        # Track unique datasets and specific trace IDs we need to export
        datasets_to_export = set()
        trace_ids_to_export = set()
        traces_by_project: Dict[str, set] = {}

        # Track related datasets
        if hasattr(experiment, "dataset_name") and experiment.dataset_name:
            datasets_to_export.add(experiment.dataset_name)

        # Check if already exists and force is not set
        if format.lower() == "csv":
            experiment_file = (
                output_dir / f"experiments_{experiment.name or experiment.id}.csv"
            )
        else:
            experiment_file = (
                output_dir / f"experiment_{experiment.name or experiment.id}.json"
            )
        skip_experiment_export = experiment_file.exists() and not force
        if skip_experiment_export:
            if debug:
                console.print(
                    f"[blue]Skipping experiment file {experiment.name} (already exists)[/blue]"
                )

        # Create experiment object to get items
        experiment_obj = Experiment(
            id=experiment.id,
            name=experiment.name,
            dataset_name=experiment.dataset_name,
            rest_client=client.rest_client,
            streamer=client._streamer,
        )

        # Get experiment items
        experiment_items = experiment_obj.get_items(
            max_results=MAX_EXPERIMENT_ITEMS_PER_FETCH, truncate=False
        )

        # Create comprehensive experiment data structure
        experiment_data = _create_experiment_data_structure(
            experiment, experiment_items
        )

        # Save experiment data (only if not skipping)
        if not skip_experiment_export:
            if format.lower() == "csv":
                _write_csv_data(
                    experiment_data, experiment_file, _experiment_to_csv_rows
                )
            else:
                _write_json_data(experiment_data, experiment_file)

            console.print(
                f"[green]Exported experiment: {experiment.name or experiment.id}[/green]"
            )
        else:
            console.print(
                f"[blue]Using existing experiment file: {experiment.name or experiment.id}[/blue]"
            )

        # Track export statistics
        stats = {
            "experiments": 1 if not skip_experiment_export else 1,  # Count as exported
            "datasets": 0,
            "traces": 0,
            "prompts": 0,
            "projects": 0,
        }

        # Export related prompts if any are referenced
        if experiment.prompt_versions:
            console.print("[blue]Exporting related prompts...[/blue]")
            stats["prompts"] = _export_experiment_prompts(
                client, experiment, output_dir, force, debug, format
            )
        else:
            # Check for prompts that might be related by name/content even if not explicitly linked
            console.print("[blue]Checking for related prompts...[/blue]")
            stats["prompts"] = _export_related_prompts_by_name(
                client, experiment, output_dir, force, debug, format
            )

        # Export related datasets and traces (same logic as the original function)
        if debug:
            console.print(
                f"[blue]DEBUG: datasets_to_export = {datasets_to_export}[/blue]"
            )
            console.print(f"[blue]DEBUG: max_traces = {max_traces}[/blue]")

        if datasets_to_export:
            console.print("[blue]Exporting related datasets...[/blue]")
            stats["datasets"] = _export_experiment_datasets(
                client, datasets_to_export, datasets_dir, format, debug
            )

        # Export traces (default to exporting all if max_traces is None)
        if max_traces is None or max_traces > 0:
            console.print("[blue]Exporting related traces...[/blue]")
            # Get trace IDs from experiment items
            for item in experiment_items:
                if hasattr(item, "trace_id") and item.trace_id:
                    trace_ids_to_export.add(item.trace_id)

                    # Get the correct project name for this trace
                    project_name = None
                    try:
                        # Try to get the project name from the trace's project_id
                        trace_content = client.get_trace_content(id=item.trace_id)
                        if trace_content and trace_content.project_id:
                            project_metadata = client.get_project(
                                id=trace_content.project_id
                            )
                            project_name = project_metadata.name
                    except Exception as e:
                        if debug:
                            console.print(
                                f"[yellow]Could not determine project name for trace {item.trace_id}: {e}[/yellow]"
                            )

                    # Fallback to experiment's dataset_name or client's project_name
                    if not project_name:
                        project_name = (
                            experiment.dataset_name
                            or client.project_name
                            or "Default Project"
                        )

                    if project_name not in traces_by_project:
                        traces_by_project[project_name] = set()
                    traces_by_project[project_name].add(item.trace_id)

            if debug:
                console.print(
                    f"[blue]DEBUG: Found {len(trace_ids_to_export)} trace IDs to export[/blue]"
                )
                console.print(
                    f"[blue]DEBUG: Projects: {list(traces_by_project.keys())}[/blue]"
                )

            # Limit trace IDs if max_traces is specified
            if max_traces and len(trace_ids_to_export) > max_traces:
                trace_ids_to_export = set(list(trace_ids_to_export)[:max_traces])

            # Export traces by project
            total_traces_exported = 0
            for project_name, project_trace_ids in traces_by_project.items():
                # Limit traces per project if max_traces is specified
                project_trace_ids_list = list(project_trace_ids)
                if max_traces and len(project_trace_ids_list) > max_traces:
                    project_trace_ids_list = project_trace_ids_list[:max_traces]

                if project_trace_ids_list:
                    project_dir = projects_dir / project_name
                    project_dir.mkdir(parents=True, exist_ok=True)

                    try:
                        traces_exported, traces_skipped = _export_specific_traces(
                            client,
                            project_name,
                            project_dir,
                            project_trace_ids_list,
                            format,
                            debug,
                            force,
                        )
                        total_traces_exported += traces_exported
                        if debug:
                            console.print(
                                f"[green]Exported {traces_exported} traces for project: {project_name}[/green]"
                            )
                    except Exception as e:
                        if debug:
                            console.print(
                                f"[yellow]Warning: Could not export traces for project {project_name}: {e}[/yellow]"
                            )

            if total_traces_exported > 0:
                console.print(
                    f"[green]Total exported {total_traces_exported} traces across {len(traces_by_project)} projects[/green]"
                )
                stats["traces"] = total_traces_exported
                stats["projects"] = len(traces_by_project)

        # Print export summary
        _print_export_summary(stats, format)

        return 1

    except Exception as e:
        console.print(f"[red]Error exporting experiment {experiment_id}: {e}[/red]")
        return 0


def _export_experiments(
    client: opik.Opik,
    output_dir: Path,
    max_results: int,
    name_pattern: Optional[str] = None,
    debug: bool = False,
    include_traces: bool = True,
    force: bool = False,
) -> int:
    """
    Export experiments with their items, dataset items, and feedback scores.

    Args:
        include_traces: Whether to include full trace data. If False, only includes trace IDs.
                      This helps avoid duplication when traces are exported separately.
        force: Whether to re-download items even if they already exist locally.
    """
    try:
        # Get all experiments using the REST client
        try:
            experiments_response = client.rest_client.experiments.find_experiments(
                page=1,
                size=max_results,
            )
            experiments = experiments_response.content or []
        except Exception as api_error:
            # Handle Pydantic validation errors from malformed API responses
            if "validation errors" in str(api_error) and "dataset_name" in str(
                api_error
            ):
                console.print(
                    "[yellow]Warning: Some experiments have a missing or empty required field (dataset_name). Skipping invalid experiments and continuing with valid ones.[/yellow]"
                )
                if debug:
                    console.print(f"[blue]Full error: {api_error}[/blue]")
                # Try to get experiments with a smaller page size to avoid the problematic ones
                try:
                    experiments_response = client.rest_client.experiments.find_experiments(
                        page=1,
                        size=FALLBACK_PAGE_SIZE,  # Smaller page size for error recovery
                    )
                    experiments = experiments_response.content or []
                except Exception:
                    # If that also fails, return empty list
                    experiments = []
            else:
                # Re-raise other API errors
                raise api_error

        if not experiments:
            console.print("[yellow]No experiments found in the workspace.[/yellow]")
            return 0

        # Filter out experiments with missing required fields to avoid validation errors
        valid_experiments = []
        skipped_count = 0

        for experiment in experiments:
            # Check if experiment has required fields
            if (
                not hasattr(experiment, "dataset_name")
                or experiment.dataset_name is None
                or experiment.dataset_name == ""
            ):
                if debug:
                    console.print(
                        f"[yellow]Skipping experiment {experiment.id or experiment.name or 'unnamed'}: missing dataset_name[/yellow]"
                    )
                skipped_count += 1
                continue
            valid_experiments.append(experiment)

        if skipped_count > 0:
            console.print(
                f"[yellow]Skipped {skipped_count} experiments with missing required fields[/yellow]"
            )

        experiments = valid_experiments

        if not experiments:
            console.print(
                "[yellow]No valid experiments found after filtering.[/yellow]"
            )
            return 0

        # Filter experiments by name pattern if specified
        if name_pattern:
            original_count = len(experiments)
            experiments = [
                experiment
                for experiment in experiments
                if _matches_name_pattern(experiment.name or "", name_pattern)
            ]
            if len(experiments) < original_count:
                console.print(
                    f"[blue]Filtered to {len(experiments)} experiments matching pattern '{name_pattern}'[/blue]"
                )

        # No additional client-side filtering needed since we use server-side dataset_id filtering

        if not experiments:
            console.print(
                "[yellow]No experiments found matching the name pattern.[/yellow]"
            )
            return 0

        exported_count = 0
        for experiment in experiments:
            try:
                if debug:
                    console.print(
                        f"[blue]Processing experiment: {experiment.name or experiment.id}[/blue]"
                    )

                # Create experiment object to get items
                experiment_obj = Experiment(
                    id=experiment.id,
                    name=experiment.name,
                    dataset_name=experiment.dataset_name,
                    rest_client=client.rest_client,
                    streamer=client._streamer,
                )

                # Get experiment items
                experiment_items = experiment_obj.get_items(
                    max_results=MAX_EXPERIMENT_ITEMS_PER_FETCH, truncate=False
                )

                # Create comprehensive experiment data structure
                experiment_data: Dict[str, Any] = {
                    "experiment": {
                        "id": experiment.id,
                        "name": experiment.name,
                        "dataset_name": experiment.dataset_name,
                        "dataset_id": experiment.dataset_id,
                        "metadata": experiment.metadata,
                        "type": experiment.type,
                        "optimization_id": experiment.optimization_id,
                        "feedback_scores": [
                            score.model_dump()
                            for score in (experiment.feedback_scores or [])
                        ],
                        "comments": [
                            comment.model_dump()
                            for comment in (experiment.comments or [])
                        ],
                        "trace_count": experiment.trace_count,
                        "created_at": (
                            experiment.created_at.isoformat()
                            if experiment.created_at
                            else None
                        ),
                        "duration": (
                            experiment.duration.model_dump()
                            if experiment.duration
                            else None
                        ),
                        "total_estimated_cost": experiment.total_estimated_cost,
                        "total_estimated_cost_avg": experiment.total_estimated_cost_avg,
                        "usage": experiment.usage,
                        "last_updated_at": (
                            experiment.last_updated_at.isoformat()
                            if experiment.last_updated_at
                            else None
                        ),
                        "created_by": experiment.created_by,
                        "last_updated_by": experiment.last_updated_by,
                        "status": experiment.status,
                        "prompt_version": (
                            experiment.prompt_version.model_dump()
                            if experiment.prompt_version
                            else None
                        ),
                        "prompt_versions": [
                            pv.model_dump() for pv in (experiment.prompt_versions or [])
                        ],
                    },
                    "items": [],
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Process each experiment item
                for item in experiment_items:
                    try:
                        item_data = {
                            "item_id": item.id,
                            "dataset_item_id": item.dataset_item_id,
                            "trace_id": item.trace_id,
                            "dataset_item_data": item.dataset_item_data,
                            "evaluation_task_output": item.evaluation_task_output,
                            "feedback_scores": item.feedback_scores,
                        }

                        # Only include full trace data if requested
                        if include_traces:
                            # Get the trace for this item
                            trace = client.search_traces(
                                project_name=experiment.dataset_name,  # Using dataset name as project context
                                filter_string=f'id = "{item.trace_id}"',
                                max_results=1,
                                truncate=False,
                            )

                            # Get spans for the trace
                            spans = []
                            if trace:
                                spans = client.search_spans(
                                    project_name=experiment.dataset_name,
                                    trace_id=item.trace_id,
                                    max_results=1000,
                                    truncate=False,
                                )

                            item_data.update(
                                {
                                    "trace": trace[0].model_dump() if trace else None,
                                    "spans": [span.model_dump() for span in spans],
                                }
                            )
                        else:
                            # Just include trace reference
                            item_data["trace_reference"] = {
                                "trace_id": item.trace_id,
                                "note": "Full trace data not included to avoid duplication. Use --include traces to export traces separately.",
                            }

                        experiment_data["items"].append(item_data)

                    except Exception as e:
                        if debug:
                            console.print(
                                f"[yellow]Warning: Could not process item {item.id}: {e}[/yellow]"
                            )
                        continue

                # Save experiment data to file
                experiment_file = (
                    output_dir / f"experiment_{experiment.name or experiment.id}.json"
                )
                with open(experiment_file, "w", encoding="utf-8") as f:
                    json.dump(experiment_data, f, indent=2, default=str)

                # Export related prompts if any are referenced
                if experiment.prompt_versions:
                    _export_experiment_prompts(
                        client, experiment, output_dir, force, debug
                    )

                exported_count += 1
                if debug:
                    console.print(
                        f"[green]Exported experiment: {experiment.name or experiment.id} with {len(experiment_data['items'])} items[/green]"
                    )

            except Exception as e:
                console.print(
                    f"[red]Error exporting experiment {experiment.name or experiment.id}: {e}[/red]"
                )
                continue

        return exported_count

    except Exception as e:
        console.print(f"[red]Error exporting experiments: {e}[/red]")
        return 0


def _export_project_by_name(
    name: str,
    workspace: str,
    output_path: str,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a project by exact name."""
    try:
        if debug:
            console.print(f"[blue]Exporting project: {name}[/blue]")

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "projects"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            console.print(f"[blue]Target directory: {output_dir}[/blue]")

        # Get projects and find exact match
        projects_response = client.rest_client.projects.find_projects()
        projects = projects_response.content or []
        matching_project = None

        for project in projects:
            if project.name == name:
                matching_project = project
                break

        if not matching_project:
            console.print(f"[red]Project '{name}' not found[/red]")
            sys.exit(1)

        if debug:
            console.print(
                f"[blue]Found project by exact match: {matching_project.name}[/blue]"
            )

        # Export the project
        exported_count = _export_single_project(
            client,
            matching_project,
            output_dir,
            filter_string,
            max_results,
            force,
            debug,
            format,
        )

        if exported_count > 0:
            console.print(
                f"[green]Successfully exported project '{name}' to {output_dir}[/green]"
            )
        else:
            console.print(
                f"[yellow]Project '{name}' already exists (use --force to re-download)[/yellow]"
            )

    except Exception as e:
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)


def _export_single_project(
    client: opik.Opik,
    project: ProjectPublic,
    output_dir: Path,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single project."""
    try:
        # Check if already exists and force is not set
        project_file = output_dir / f"project_{project.name}.json"

        if project_file.exists() and not force:
            if debug:
                console.print(f"[blue]Skipping {project.name} (already exists)[/blue]")
            return 0

        # Create project-specific directory for traces
        project_traces_dir = output_dir / project.name
        project_traces_dir.mkdir(parents=True, exist_ok=True)

        # Export related traces for this project
        traces_exported, traces_skipped = _export_traces(
            client,
            project.name,
            project_traces_dir,
            max_results or 1000,  # Use provided max_results or default to 1000
            filter_string,
            None,  # name_pattern
            format,
            debug,
            force,
        )

        # Project export only exports traces - datasets and prompts must be exported separately
        if traces_exported > 0 or traces_skipped > 0:
            if debug:
                console.print(
                    f"[blue]Exported project: {project.name} with {traces_exported} traces[/blue]"
                )
            return 1
        else:
            return 0

    except Exception as e:
        console.print(f"[red]Error exporting project {project.name}: {e}[/red]")
        return 0


def _export_prompt_by_name(
    name: str,
    workspace: str,
    output_path: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a prompt by exact name."""
    try:
        if debug:
            console.print(f"[blue]Exporting prompt: {name}[/blue]")

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "prompts"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            console.print(f"[blue]Target directory: {output_dir}[/blue]")

        # Try to get prompt by exact name
        try:
            prompt = client.get_prompt(name)
            if not prompt:
                console.print(f"[red]Prompt '{name}' not found[/red]")
                return

            if debug:
                console.print(
                    f"[blue]Found prompt by direct lookup: {prompt.name}[/blue]"
                )
        except Exception as e:
            console.print(f"[red]Prompt '{name}' not found: {e}[/red]")
            return

        # Export the prompt
        exported_count = _export_single_prompt(
            client, prompt, output_dir, max_results, force, debug, format
        )

        if exported_count > 0:
            console.print(
                f"[green]Successfully exported prompt '{name}' to {output_dir}[/green]"
            )
        else:
            console.print(
                f"[yellow]Prompt '{name}' already exists (use --force to re-download)[/yellow]"
            )

    except Exception as e:
        console.print(f"[red]Error exporting prompt: {e}[/red]")
        sys.exit(1)


def _export_single_prompt(
    client: opik.Opik,
    prompt: Prompt,
    output_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single prompt."""
    try:
        # Check if already exists and force is not set
        if format.lower() == "csv":
            prompt_file = output_dir / f"prompts_{prompt.name.replace('/', '_')}.csv"
        else:
            prompt_file = output_dir / f"prompt_{prompt.name.replace('/', '_')}.json"

        if prompt_file.exists() and not force:
            if debug:
                console.print(f"[blue]Skipping {prompt.name} (already exists)[/blue]")
            return 0

        # Get prompt history
        prompt_history = client.get_prompt_history(prompt.name)

        # Create prompt data structure
        prompt_data = {
            "name": prompt.name,
            "current_version": {
                "prompt": prompt.prompt,
                "metadata": prompt.metadata,
                "type": prompt.type if prompt.type else None,
                "commit": prompt.commit,
            },
            "history": [
                {
                    "prompt": version.prompt,
                    "metadata": version.metadata,
                    "type": version.type if version.type else None,
                    "commit": version.commit,
                }
                for version in prompt_history
            ],
            "downloaded_at": datetime.now().isoformat(),
        }

        # Save to file using the appropriate format
        if format.lower() == "csv":
            _write_csv_data(prompt_data, prompt_file, _prompt_to_csv_rows)
        else:
            _write_json_data(prompt_data, prompt_file)

        if debug:
            console.print(f"[blue]Exported prompt: {prompt.name}[/blue]")
        return 1

    except Exception as e:
        console.print(f"[red]Error exporting prompt {prompt.name}: {e}[/red]")
        return 0


def _export_experiment_by_name(
    name: str,
    workspace: str,
    output_path: str,
    dataset: Optional[str],
    max_traces: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export an experiment by exact name."""
    try:
        if debug:
            console.print(f"[blue]Exporting experiment: {name}[/blue]")

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "experiments"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            console.print(f"[blue]Target directory: {output_dir}[/blue]")

        # Try to get experiment by exact name
        try:
            experiments = client.get_experiments_by_name(name)
            if not experiments:
                console.print(f"[red]Experiment '{name}' not found[/red]")
                sys.exit(1)

            experiment = experiments[0]  # Should be only one with exact name
            if debug:
                console.print(
                    f"[blue]Found experiment by direct lookup: {experiment.name}[/blue]"
                )
        except Exception as e:
            console.print(f"[red]Experiment '{name}' not found: {e}[/red]")
            sys.exit(1)

        # Export the experiment
        exported_count = _export_specific_experiment_by_id(
            client,
            output_dir,
            experiment.id,
            dataset,
            max_traces,
            force,
            debug,
            format,
        )

        if exported_count > 0:
            console.print(
                f"[green]Successfully exported experiment '{name}' to {output_dir}[/green]"
            )
        else:
            console.print(
                f"[yellow]Experiment '{name}' already exists (use --force to re-download)[/yellow]"
            )

    except Exception as e:
        console.print(f"[red]Error exporting experiment: {e}[/red]")
        sys.exit(1)


def _export_dataset_by_name(
    name: str,
    workspace: str,
    output_path: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a dataset by exact name."""
    try:
        if debug:
            console.print(f"[blue]Exporting dataset: {name}[/blue]")

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "datasets"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            console.print(f"[blue]Target directory: {output_dir}[/blue]")

        # Try to get dataset by exact name
        try:
            dataset = client.get_dataset(name)
            if debug:
                console.print(
                    f"[blue]Found dataset by direct lookup: {dataset.name}[/blue]"
                )
        except Exception as e:
            console.print(f"[red]Dataset '{name}' not found: {e}[/red]")
            sys.exit(1)

        # Export the dataset
        exported_count = _export_single_dataset(
            dataset, output_dir, max_results, force, debug, format
        )

        if exported_count > 0:
            console.print(
                f"[green]Successfully exported dataset '{name}' to {output_dir}[/green]"
            )
        else:
            console.print(
                f"[yellow]Dataset '{name}' already exists (use --force to re-download)[/yellow]"
            )

    except Exception as e:
        console.print(f"[red]Error exporting dataset: {e}[/red]")
        sys.exit(1)


def _export_single_dataset(
    dataset: Dataset,
    output_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single dataset."""
    try:
        # Check if already exists and force is not set
        if format.lower() == "csv":
            dataset_file = output_dir / f"datasets_{dataset.name}.csv"
        else:
            dataset_file = output_dir / f"dataset_{dataset.name}.json"

        if dataset_file.exists() and not force:
            if debug:
                console.print(f"[blue]Skipping {dataset.name} (already exists)[/blue]")
            return 0

        # Get dataset items
        if debug:
            console.print(f"[blue]Getting items for dataset: {dataset.name}[/blue]")
        dataset_items = dataset.get_items()

        # Format items for export
        formatted_items = []
        for item in dataset_items:
            formatted_item = {
                "input": item.get("input"),
                "expected_output": item.get("expected_output"),
                "metadata": item.get("metadata"),
            }
            formatted_items.append(formatted_item)

        # Create dataset data structure
        dataset_data = {
            "name": dataset.name,
            "description": dataset.description,
            "items": formatted_items,
            "downloaded_at": datetime.now().isoformat(),
        }

        # Save to file using the appropriate format
        if format.lower() == "csv":
            _write_csv_data(dataset_data, dataset_file, _dataset_to_csv_rows)
        else:
            _write_json_data(dataset_data, dataset_file)

        if debug:
            console.print(f"[blue]Exported dataset: {dataset.name}[/blue]")
        return 1

    except Exception as e:
        console.print(f"[red]Error exporting dataset {dataset.name}: {e}[/red]")
        return 0


@click.group(name="export")
@click.argument("workspace", type=str)
@click.pass_context
def export_group(ctx: click.Context, workspace: str) -> None:
    """Export data from Opik workspace."""
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace


@export_group.command(name="dataset")
@click.argument("name", type=str)
@click.option(
    "--max-results",
    type=int,
    help="Maximum number of datasets to export. Limits the total number of datasets downloaded.",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Re-download items even if they already exist locally.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
)
@click.pass_context
def export_dataset(
    ctx: click.Context,
    name: str,
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a dataset by exact name to workspace/datasets."""
    # Get workspace from context
    workspace = ctx.obj["workspace"]
    _export_dataset_by_name(name, workspace, path, max_results, force, debug, format)


@export_group.command(name="project")
@click.argument("name", type=str)
@click.option(
    "--filter",
    type=str,
    help="Filter string to narrow down traces using Opik Query Language (OQL).",
)
@click.option(
    "--max-results",
    type=int,
    help="Maximum number of traces to export. Limits the total number of traces downloaded.",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Re-download items even if they already exist locally.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
)
@click.pass_context
def export_project(
    ctx: click.Context,
    name: str,
    filter: Optional[str],
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a project by exact name to workspace/projects."""
    # Get workspace from context
    workspace = ctx.obj["workspace"]
    _export_project_by_name(
        name, workspace, path, filter, max_results, force, debug, format
    )


@export_group.command(name="experiment")
@click.argument("name", type=str)
@click.option(
    "--dataset",
    type=str,
    help="Filter experiments by exact dataset name. Server-side filtering for efficiency.",
)
@click.option(
    "--max-traces",
    type=int,
    help="Maximum number of traces to export. Limits the total number of traces downloaded.",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Re-download items even if they already exist locally.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
)
@click.pass_context
def export_experiment(
    ctx: click.Context,
    name: str,
    dataset: Optional[str],
    max_traces: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export an experiment by exact name to workspace/experiments."""
    # Get workspace from context
    workspace = ctx.obj["workspace"]
    _export_experiment_by_name(
        name, workspace, path, dataset, max_traces, force, debug, format
    )


@export_group.command(name="prompt")
@click.argument("name", type=str)
@click.option(
    "--max-results",
    type=int,
    help="Maximum number of prompts to export. Limits the total number of prompts downloaded.",
)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Re-download items even if they already exist locally.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the export process.",
)
@click.option(
    "--format",
    type=click.Choice(["json", "csv"], case_sensitive=False),
    default="json",
    help="Format for exporting data. Defaults to json.",
)
@click.pass_context
def export_prompt(
    ctx: click.Context,
    name: str,
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a prompt by exact name to workspace/prompts."""
    # Get workspace from context
    workspace = ctx.obj["workspace"]
    _export_prompt_by_name(name, workspace, path, max_results, force, debug, format)
