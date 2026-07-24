"""Dataset export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional

import click

import opik
from .utils import (
    console,
    debug_print,
    dataset_to_csv_rows,
    prepare_project_export_dir,
    should_skip_file,
    write_csv_data,
    write_json_data,
    print_export_summary,
)


def export_single_dataset(
    dataset: opik.Dataset,
    output_dir: Path,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
) -> int:
    """Export a single dataset. The file is named by dataset ID; the human
    name is stored inside the file."""
    try:
        # File is keyed by dataset ID (the name lives inside the file).
        dataset_id = dataset.id
        ext = "csv" if format.lower() == "csv" else "json"
        dataset_file = output_dir / f"dataset_{dataset_id}.{ext}"

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

        # Dataset-level tags are stored on the version, not surfaced by
        # get_dataset(); fetch them explicitly. Never let a tags lookup abort
        # the export, but log it so a missing-tags export is diagnosable.
        try:
            dataset_tags = dataset.get_tags()
        except Exception as tag_error:
            dataset_tags = None
            console.print(
                f"[yellow]Warning: Could not fetch tags for dataset "
                f"'{dataset.name}' ({dataset_id}): {tag_error}. "
                f"Exporting without tags.[/yellow]"
            )

        # Create dataset data structure
        dataset_data = {
            "id": dataset_id,
            "name": dataset.name,
            "description": dataset.description,
            "tags": dataset_tags,
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
    project_name: str,
    output_path: str,
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
) -> None:
    """Export a dataset by exact name from the given project."""
    try:
        if debug:
            debug_print(f"Exporting dataset: {name}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Resolve the project to its ID and create projects/<id>/datasets.
        _project_id, project_dir = prepare_project_export_dir(
            client, output_path, workspace, project_name
        )
        output_dir = project_dir / "datasets"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get dataset by exact name within the project
        try:
            dataset = client.get_dataset(name, project_name=project_name)
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
    project_name: str,
    format: str,
    debug: bool,
    force: bool,
) -> tuple[int, int, int]:
    """Export datasets related to an experiment.

    Args:
        client: Opik client instance
        datasets_to_export: Set of dataset names to export
        datasets_dir: Directory to save datasets
        project_name: Project the experiment (and its datasets) belong to
        format: Export format ('json' or 'csv')
        debug: Enable debug output
        force: Re-download datasets even if they already exist locally

    Returns:
        Tuple of (exported_count, skipped_count, error_count)
    """
    exported_count = 0
    skipped_count = 0
    error_count = 0

    for dataset_name in datasets_to_export:
        try:
            datasets_dir.mkdir(parents=True, exist_ok=True)

            dataset_obj = opik.Dataset(
                name=dataset_name,
                description=None,  # Description not available from experiment
                project_name=project_name,
                rest_client=client.rest_client,
            )
            # File is keyed by dataset ID (the name lives inside the file).
            dataset_id = dataset_obj.id
            ext = "csv" if format.lower() == "csv" else "json"
            dataset_file = datasets_dir / f"dataset_{dataset_id}.{ext}"

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

            dataset_items = dataset_obj.get_items()

            try:
                dataset_tags = dataset_obj.get_tags()
            except Exception as tag_error:
                dataset_tags = None
                console.print(
                    f"[yellow]Warning: Could not fetch tags for dataset "
                    f"'{dataset_name}' ({dataset_id}): {tag_error}. "
                    f"Exporting without tags.[/yellow]"
                )

            dataset_data = {
                "dataset": {
                    "name": dataset_name,
                    "id": dataset_id,
                    "tags": dataset_tags,
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
            error_count += 1
            if debug:
                console.print(
                    f"[yellow]Warning: Could not export dataset {dataset_name}: {e}[/yellow]"
                )
            else:
                console.print(f"[red]Error exporting dataset {dataset_name}: {e}[/red]")

    return exported_count, skipped_count, error_count


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
    """Export a dataset by exact name to projects/<project>/datasets."""
    # Get workspace, project, and API key from context
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_dataset_by_name(
        name, workspace, project_name, path, max_results, force, debug, format, api_key
    )
