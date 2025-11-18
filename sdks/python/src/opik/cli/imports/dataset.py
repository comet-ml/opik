"""Dataset import functionality."""

import json
from pathlib import Path
from typing import Optional

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
) -> int:
    """Import datasets from a directory."""
    try:
        dataset_files = list(source_dir.glob("dataset_*.json"))

        if not dataset_files:
            console.print("[yellow]No dataset files found in the directory[/yellow]")
            return 0

        imported_count = 0
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
                    continue

                if dry_run:
                    console.print(f"[blue]Would import dataset: {dataset_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing dataset: {dataset_name}[/blue]")

                # Create dataset
                dataset = client.create_dataset(name=dataset_name)

                # Import dataset items
                items = dataset_data.get("items", [])
                if items:
                    dataset.insert(items)

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

        if error_count > 0 and imported_count == 0:
            # If there were errors and nothing was imported, return -1 to indicate failure
            return -1

        return imported_count

    except Exception as e:
        console.print(f"[red]Error importing datasets: {e}[/red]")
        return -1
