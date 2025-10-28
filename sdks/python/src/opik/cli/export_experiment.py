"""Experiment export functionality."""

import sys
from pathlib import Path
from typing import Optional

import click
from rich.console import Console

import opik
from opik.cli.export_utils import (
    create_experiment_data_structure,
    debug_print,
    write_json_data,
    print_export_summary,
)
from opik.cli.export_dataset import export_experiment_datasets
from opik.cli.export_prompt import (
    export_experiment_prompts,
    export_related_prompts_by_name,
)
from opik.cli.export_project import export_traces

console = Console()


def export_experiment_by_id(
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
        experiment = client.get_experiment_by_id(experiment_id)
        if not experiment:
            console.print(f"[red]Experiment '{experiment_id}' not found[/red]")
            return 0

        debug_print(f"Found experiment: {experiment.name}", debug)

        # Get experiment items first
        experiment_items = experiment.get_items()

        # Create experiment data structure
        experiment_data = create_experiment_data_structure(experiment, experiment_items)

        # Save experiment data
        experiment_file = output_dir / f"experiment_{experiment.name}.json"
        if not experiment_file.exists() or force:
            write_json_data(experiment_data, experiment_file)
            debug_print(f"Exported experiment: {experiment.name}", debug)
        else:
            debug_print(
                f"Skipping experiment {experiment.name} (already exists)", debug
            )

        # Export related datasets and prompts
        stats = {"datasets": 0, "prompts": 0, "traces": 0}
        if experiment.dataset_name:
            datasets_dir = output_dir / "datasets"
            datasets_to_export = {experiment.dataset_name}
            stats["datasets"] = export_experiment_datasets(
                client, datasets_to_export, datasets_dir, format, debug
            )
            stats["prompts"] = export_experiment_prompts(
                client, experiment, output_dir, force, debug, format
            )
            stats["prompts"] += export_related_prompts_by_name(
                client, experiment, output_dir, force, debug, format
            )

        # Export specific traces if requested
        if experiment.dataset_name:
            traces_exported, traces_skipped = export_traces(
                client,
                experiment.dataset_name,
                output_dir,
                max_traces or 1000,
                None,  # filter_string
                None,  # project_name_filter
                format,
                debug,
                force,
            )

        if debug:
            console.print(
                f"[green]Experiment {experiment.name} exported with stats: {stats}[/green]"
            )

        return 1

    except Exception as e:
        console.print(f"[red]Error exporting experiment {experiment_id}: {e}[/red]")
        return 0


def export_experiment_by_name(
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
            debug_print(f"Exporting experiment: {name}", debug)

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "experiments"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get experiment by exact name
        try:
            experiment = client.get_experiment_by_name(name)
            if not experiment:
                console.print(f"[red]Experiment '{name}' not found[/red]")
                return

            if debug:
                debug_print(
                    f"Found experiment by direct lookup: {experiment.name}", debug
                )
        except Exception as e:
            console.print(f"[red]Experiment '{name}' not found: {e}[/red]")
            return

        # Export the experiment
        exported_count = export_experiment_by_id(
            client, output_dir, experiment.id, dataset, max_traces, force, debug, format
        )

        # Collect statistics for summary
        stats = {
            "experiments": 1 if exported_count > 0 else 0,
            "experiments_skipped": 0 if exported_count > 0 else 1,
        }

        # Count trace files
        trace_files = list(output_dir.glob("trace_*.json"))
        trace_csv_files = list(output_dir.glob("trace_*.csv"))
        total_trace_files = len(trace_files) + len(trace_csv_files)

        stats["traces"] = total_trace_files
        stats["traces_skipped"] = (
            0  # We don't track skipped traces in current implementation
        )

        # Show export summary
        print_export_summary(stats, format)

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


@click.command(name="experiment")
@click.argument("name", type=str)
@click.option(
    "--dataset",
    type=str,
    help="Dataset name to filter traces by. If not provided, all traces will be exported.",
)
@click.option(
    "--max-traces",
    type=int,
    help="Maximum number of traces to export per experiment. Limits the total number of traces downloaded.",
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
def export_experiment_command(
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
    export_experiment_by_name(
        name, workspace, path, dataset, max_traces, force, debug, format
    )
