"""Experiment export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional, List

import click
from rich.console import Console
from rich.progress import (
    Progress,
    SpinnerColumn,
    TextColumn,
    BarColumn,
    TaskProgressColumn,
)

import opik
from .utils import (
    create_experiment_data_structure,
    debug_print,
    write_json_data,
    write_csv_data,
    print_export_summary,
    should_skip_file,
    trace_to_csv_rows,
)
from .dataset import export_experiment_datasets
from .prompt import (
    export_experiment_prompts,
    export_related_prompts_by_name,
)

console = Console()


def export_traces_by_ids(
    client: opik.Opik,
    trace_ids: List[str],
    workspace_root: Path,
    max_traces: Optional[int],
    format: str,
    debug: bool,
    force: bool,
) -> tuple[int, int]:
    """Export traces by their IDs (e.g., from experiment items).

    Traces are saved in projects/PROJECT_NAME/ directory based on each trace's project.
    """
    exported_count = 0
    skipped_count = 0

    # Limit the number of traces if max_traces is specified
    if max_traces:
        trace_ids = trace_ids[:max_traces]

    if not trace_ids:
        if debug:
            debug_print("No trace IDs provided for export", debug)
        return 0, 0

    if debug:
        debug_print(f"Exporting {len(trace_ids)} trace(s) by ID", debug)

    # Cache project names to avoid repeated API calls
    project_name_cache: dict[str, str] = {}

    # Use progress bar for trace export
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        BarColumn(),
        TaskProgressColumn(),
        console=console,
    ) as progress:
        task = progress.add_task(
            f"Exporting {len(trace_ids)} traces...", total=len(trace_ids)
        )

        for trace_id in trace_ids:
            try:
                # Get trace by ID
                trace = client.get_trace_content(trace_id)

                # Get project name for this trace
                if not trace.project_id:
                    console.print(
                        f"[yellow]Warning: Trace {trace_id} has no project_id, skipping[/yellow]"
                    )
                    progress.update(task, advance=1)
                    continue

                # Get project name (use cache if available)
                if trace.project_id not in project_name_cache:
                    try:
                        project = client.get_project(trace.project_id)
                        project_name_cache[trace.project_id] = project.name
                    except Exception as e:
                        console.print(
                            f"[yellow]Warning: Could not get project for trace {trace_id}: {e}[/yellow]"
                        )
                        progress.update(task, advance=1)
                        continue

                project_name = project_name_cache[trace.project_id]

                # Get spans for this trace
                spans = client.search_spans(
                    trace_id=trace_id,
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

                # Save trace in projects/PROJECT_NAME/ directory
                project_dir = workspace_root / "projects" / project_name
                project_dir.mkdir(parents=True, exist_ok=True)

                # Determine file path based on format
                if format.lower() == "csv":
                    file_path = project_dir / f"trace_{trace_id}.csv"
                else:
                    file_path = project_dir / f"trace_{trace_id}.json"

                # Check if file already exists and should be skipped
                if should_skip_file(file_path, force):
                    if debug:
                        debug_print(
                            f"Skipping trace {trace_id} (already exists)",
                            debug,
                        )
                    skipped_count += 1
                    progress.update(task, advance=1)
                    continue

                # Save to file using the appropriate format
                try:
                    if format.lower() == "csv":
                        write_csv_data(trace_data, file_path, trace_to_csv_rows)
                        if debug:
                            debug_print(f"Wrote CSV file: {file_path}", debug)
                    else:
                        write_json_data(trace_data, file_path)
                        if debug:
                            debug_print(f"Wrote JSON file: {file_path}", debug)

                    exported_count += 1
                    progress.update(
                        task,
                        advance=1,
                        description=f"Exported {exported_count}/{len(trace_ids)} traces",
                    )
                except Exception as write_error:
                    console.print(
                        f"[red]Error writing trace {trace_id} to file: {write_error}[/red]"
                    )
                    if debug:
                        import traceback

                        debug_print(f"Traceback: {traceback.format_exc()}", debug)
                    progress.update(task, advance=1)
                    continue

            except Exception as e:
                console.print(f"[red]Error exporting trace {trace_id}: {e}[/red]")
                if debug:
                    import traceback

                    debug_print(f"Traceback: {traceback.format_exc()}", debug)
                progress.update(task, advance=1)
                continue

    return exported_count, skipped_count


def export_experiment_by_id(
    client: opik.Opik,
    output_dir: Path,
    experiment_id: str,
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

        # Get experiment items first (this can be slow for large experiments)
        console.print("[blue]Fetching experiment items...[/blue]")
        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            console=console,
        ) as progress:
            task = progress.add_task("Getting experiment items...", total=None)
            experiment_items = experiment.get_items()
            progress.update(task, description="Got experiment items")

        # Create experiment data structure
        experiment_data = create_experiment_data_structure(experiment, experiment_items)

        # Save experiment data
        # Include experiment ID in filename to handle multiple experiments with same name
        experiment_file = (
            output_dir / f"experiment_{experiment.name}_{experiment.id}.json"
        )
        file_already_exists = experiment_file.exists()
        experiment_file_written = False

        if not file_already_exists or force:
            write_json_data(experiment_data, experiment_file)
            experiment_file_written = True
            debug_print(
                f"Exported experiment: {experiment.name} (ID: {experiment.id})", debug
            )
        else:
            debug_print(
                f"Skipping experiment {experiment.name} (ID: {experiment.id}) (already exists)",
                debug,
            )

        # Export related datasets and prompts
        stats = {"datasets": 0, "prompts": 0, "traces": 0}
        if experiment.dataset_name:
            # Datasets should go in root/workspace/datasets, not root/workspace/experiments/datasets
            datasets_dir = output_dir.parent / "datasets"
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

        # Export traces from experiment items
        # Get unique trace IDs from experiment items
        trace_ids = list(
            set(item.trace_id for item in experiment_items if item.trace_id)
        )

        if trace_ids:
            # Get workspace root (output_dir is workspace/experiments, so parent is workspace root)
            workspace_root = output_dir.parent
            traces_exported, traces_skipped = export_traces_by_ids(
                client,
                trace_ids,
                workspace_root,
                max_traces,
                format,
                debug,
                force,
            )
            stats["traces"] = traces_exported
        else:
            if debug:
                debug_print("No trace IDs found in experiment items", debug)
            stats["traces"] = 0

        if debug:
            console.print(
                f"[green]Experiment {experiment.name} exported with stats: {stats}[/green]"
            )

        # Return 1 if the experiment file was written, 0 if it was skipped
        return 1 if experiment_file_written else 0

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
    api_key: Optional[str] = None,
) -> None:
    """Export an experiment by exact name."""
    try:
        if debug:
            debug_print(f"Exporting experiment: {name}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "experiments"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get experiments by exact name
        try:
            experiments = client.get_experiments_by_name(name)
            if not experiments:
                console.print(f"[red]Experiment '{name}' not found[/red]")
                return

            if debug:
                debug_print(
                    f"Found {len(experiments)} experiment(s) with name '{name}'", debug
                )

            if len(experiments) > 1:
                console.print(
                    f"[blue]Found {len(experiments)} experiments with name '{name}', exporting all of them[/blue]"
                )
        except Exception as e:
            console.print(f"[red]Experiment '{name}' not found: {e}[/red]")
            return

        # Export all matching experiments
        exported_count = 0
        skipped_count = 0

        for experiment in experiments:
            if debug:
                debug_print(
                    f"Exporting experiment: {experiment.name} (ID: {experiment.id})",
                    debug,
                )

            result = export_experiment_by_id(
                client, output_dir, experiment.id, max_traces, force, debug, format
            )

            if result > 0:
                exported_count += 1
            else:
                skipped_count += 1

        # Count trace files
        trace_files = list(output_dir.glob("trace_*.json"))
        trace_csv_files = list(output_dir.glob("trace_*.csv"))
        total_trace_files = len(trace_files) + len(trace_csv_files)

        # Collect statistics for summary
        stats = {
            "experiments": exported_count,
            "experiments_skipped": skipped_count,
            "traces": total_trace_files,
            "traces_skipped": 0,  # We don't track skipped traces in current implementation
        }

        # Show export summary
        print_export_summary(stats, format)

        if exported_count > 0:
            if len(experiments) > 1:
                console.print(
                    f"[green]Successfully exported {exported_count} experiment(s) with name '{name}' to {output_dir}[/green]"
                )
            else:
                console.print(
                    f"[green]Successfully exported experiment '{name}' to {output_dir}[/green]"
                )
        else:
            console.print(
                f"[yellow]All {len(experiments)} experiment(s) with name '{name}' already exist (use --force to re-download)[/yellow]"
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
    # Get workspace and API key from context
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_experiment_by_name(
        name, workspace, path, dataset, max_traces, force, debug, format, api_key
    )
