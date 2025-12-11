"""Dataset import functionality."""

import json
from pathlib import Path
from typing import Dict, Optional

import opik
from rich.console import Console

from .utils import matches_name_pattern

console = Console()


def import_datasets_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
) -> Dict[str, int]:
    """Import datasets from a directory.

    Returns:
        Dictionary with keys: 'datasets', 'datasets_skipped', 'datasets_errors'
    """
    try:
        dataset_files = list(source_dir.glob("dataset_*.json"))

        if not dataset_files:
            console.print("[yellow]No dataset files found in the directory[/yellow]")
            return {"datasets": 0, "datasets_skipped": 0, "datasets_errors": 0}

        imported_count = 0
        skipped_count = 0
        error_count = 0
        for dataset_file in dataset_files:
            try:
                with open(dataset_file, "r", encoding="utf-8") as f:
                    dataset_data = json.load(f)

                # Handle two export formats:
                # 1. {"name": "...", "items": [...]} - from export_single_dataset
                # 2. {"dataset": {"name": "...", "id": "..."}, "items": [...]} - from export_experiment_datasets
                dataset_name = dataset_data.get("name") or (
                    dataset_data.get("dataset", {}).get("name")
                    if dataset_data.get("dataset")
                    else None
                )

                # Check if name is missing or empty
                if not dataset_name or (
                    isinstance(dataset_name, str) and not dataset_name.strip()
                ):
                    console.print(
                        f"[red]Error: Dataset file {dataset_file.name} is missing or has an empty name field[/red]"
                    )
                    error_count += 1
                    continue

                # Strip whitespace from name
                dataset_name = dataset_name.strip()

                # Filter by name pattern if specified
                if name_pattern and not matches_name_pattern(
                    dataset_name, name_pattern
                ):
                    if debug:
                        console.print(
                            f"[blue]Skipping dataset {dataset_name} (doesn't match pattern)[/blue]"
                        )
                    skipped_count += 1
                    continue

                if dry_run:
                    console.print(f"[blue]Would import dataset: {dataset_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing dataset: {dataset_name}[/blue]")

                # Get or create dataset (handles case where dataset already exists)
                try:
                    dataset = client.get_dataset(dataset_name)
                    if debug:
                        console.print(
                            f"[blue]Dataset '{dataset_name}' already exists, using existing dataset[/blue]"
                        )
                except Exception:
                    # Dataset doesn't exist, create it
                    dataset = client.create_dataset(name=dataset_name)
                    if debug:
                        console.print(
                            f"[blue]Created new dataset: {dataset_name}[/blue]"
                        )

                # Import dataset items
                items = dataset_data.get("items", [])
                if items:
                    # Remove 'id' field from items before inserting (IDs are auto-generated)
                    items_to_insert = []
                    for item in items:
                        if isinstance(item, dict):
                            # Create a copy without the 'id' field
                            item_copy = {k: v for k, v in item.items() if k != "id"}
                            items_to_insert.append(item_copy)
                        else:
                            items_to_insert.append(item)

                    if items_to_insert:
                        dataset.insert(items_to_insert)
                        if debug:
                            console.print(
                                f"[blue]Inserted {len(items_to_insert)} items into dataset '{dataset_name}'[/blue]"
                            )
                    else:
                        console.print(
                            f"[yellow]Warning: No items to insert for dataset '{dataset_name}' (all items were empty after removing 'id' field)[/yellow]"
                        )

                imported_count += 1
                if debug:
                    console.print(
                        f"[green]Imported dataset: {dataset_name} with {len(items)} items[/green]"
                    )

            except Exception as e:
                console.print(
                    f"[red]Error importing dataset from {dataset_file.name}: {e}[/red]"
                )
                error_count += 1
                continue

        return {
            "datasets": imported_count,
            "datasets_skipped": skipped_count,
            "datasets_errors": error_count,
        }

    except Exception as e:
        console.print(f"[red]Error importing datasets: {e}[/red]")
        return {"datasets": 0, "datasets_skipped": 0, "datasets_errors": 1}
