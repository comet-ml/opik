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
        for dataset_file in dataset_files:
            try:
                with open(dataset_file, "r", encoding="utf-8") as f:
                    dataset_data = json.load(f)

                dataset_name = dataset_data.get("name", "")

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
                    f"[red]Error importing dataset from {dataset_file}: {e}[/red]"
                )
                continue

        return imported_count

    except Exception as e:
        console.print(f"[red]Error importing datasets: {e}[/red]")
        return 0
