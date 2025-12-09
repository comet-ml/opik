"""Dataset export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

import click
from rich.console import Console

import opik
from .utils import (
    debug_print,
    dataset_to_csv_rows,
    should_skip_file,
    write_csv_data,
    write_json_data,
    print_export_summary,
)

console = Console()


def export_single_dataset(
    dataset: opik.Dataset,
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
            dataset_file = output_dir / f"dataset_{dataset.name}.csv"
        else:
            dataset_file = output_dir / f"dataset_{dataset.name}.json"

        if should_skip_file(dataset_file, force):
            if debug:
                debug_print(f"Skipping {dataset.name} (already exists)", debug)
            return 0

        # Get dataset items
        if debug:
            debug_print(f"Getting items for dataset: {dataset.name}", debug)
        dataset_items = dataset.get_items()

        # Format items for export
        # Use all fields from each item (datasets can have any user-defined keys/values)
        formatted_items = []
        for item in dataset_items:
            # Create a copy of the item, excluding the 'id' field if present
            # (id is internal and not part of the dataset item content)
            formatted_item = {k: v for k, v in item.items() if k != "id"}
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
            write_csv_data(dataset_data, dataset_file, dataset_to_csv_rows)
        else:
            write_json_data(dataset_data, dataset_file)

        if debug:
            debug_print(f"Exported dataset: {dataset.name}", debug)
        return 1

    except Exception as e:
        console.print(f"[red]Error exporting dataset {dataset.name}: {e}[/red]")
        return 0


def export_dataset_by_name(
    name: str,
    workspace: str,
    output_path: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
) -> None:
    """Export a dataset by exact name."""
    try:
        if debug:
            debug_print(f"Exporting dataset: {name}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "datasets"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get dataset by exact name
        try:
            dataset = client.get_dataset(name)
            if debug:
                debug_print(f"Found dataset by direct lookup: {dataset.name}", debug)
        except Exception as e:
            console.print(f"[red]Dataset '{name}' not found: {e}[/red]")
            sys.exit(1)

        # Export the dataset
        exported_count = export_single_dataset(
            dataset, output_dir, max_results, force, debug, format
        )

        # Collect statistics for summary
        stats = {
            "datasets": 1 if exported_count > 0 else 0,
            "datasets_skipped": 0 if exported_count > 0 else 1,
        }

        # Show export summary
        print_export_summary(stats, format)

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


def export_experiment_datasets(
    client: opik.Opik,
    datasets_to_export: set[str],
    datasets_dir: Path,
    format: str,
    debug: bool,
    force: bool,
) -> tuple[int, int]:
    """Export datasets related to an experiment.

    Args:
        client: Opik client instance
        datasets_to_export: Set of dataset names to export
        datasets_dir: Directory to save datasets
        format: Export format ('json' or 'csv')
        debug: Enable debug output
        force: Re-download datasets even if they already exist locally

    Returns:
        Tuple of (exported_count, skipped_count)
    """
    exported_count = 0
    skipped_count = 0

    for dataset_name in datasets_to_export:
        try:
            # Use format parameter to determine file extension
            if format.lower() == "csv":
                dataset_file = datasets_dir / f"dataset_{dataset_name}.csv"
            else:
                dataset_file = datasets_dir / f"dataset_{dataset_name}.json"
            datasets_dir.mkdir(parents=True, exist_ok=True)

            # Check if file already exists and should be skipped
            if should_skip_file(dataset_file, force):
                if debug:
                    debug_print(
                        f"Skipping dataset {dataset_name} (already exists)", debug
                    )
                else:
                    console.print(
                        f"[yellow]Skipping dataset: {dataset_name} (already exists)[/yellow]"
                    )
                skipped_count += 1
                continue

            dataset_obj = opik.Dataset(
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
                # Use all fields from each item, excluding 'id' (internal field)
                "items": [
                    {k: v for k, v in item.items() if k != "id"}
                    for item in dataset_items
                ],
                "downloaded_at": datetime.now().isoformat(),
            }

            # Save to file using the appropriate format
            if format.lower() == "csv":
                write_csv_data(dataset_data, dataset_file, dataset_to_csv_rows)
            else:
                write_json_data(dataset_data, dataset_file)

            console.print(f"[green]Exported dataset: {dataset_name}[/green]")
            exported_count += 1
        except Exception as e:
            if debug:
                console.print(
                    f"[yellow]Warning: Could not export dataset {dataset_name}: {e}[/yellow]"
                )
            else:
                console.print(f"[red]Error exporting dataset {dataset_name}: {e}[/red]")

    return exported_count, skipped_count


@click.command(name="dataset")
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
    default="opik_exports",
    help="Directory to save exported data. Defaults to opik_exports.",
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
def export_dataset_command(
    ctx: click.Context,
    name: str,
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export a dataset by exact name to workspace/datasets."""
    # Get workspace and API key from context
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_dataset_by_name(
        name, workspace, path, max_results, force, debug, format, api_key
    )
