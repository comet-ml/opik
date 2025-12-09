"""Experiment export functionality."""

import sys
from datetime import datetime
from pathlib import Path
from typing import Optional, List, Dict

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
from opik import exceptions
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
    export_related_prompts_by_name,
    export_prompts_by_ids,
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
    trace_ids_collector: Optional[set[str]] = None,
) -> tuple[Dict[str, int], int]:
    """Export a specific experiment by ID, including related datasets and traces.

    Returns:
        Tuple of (stats dictionary, file_written flag) where:
        - stats: Dictionary with keys "datasets", "prompts", "traces" and their counts
        - file_written: 1 if experiment file was written, 0 if skipped or error
    """
    try:
        console.print(f"[blue]Fetching experiment by ID: {experiment_id}[/blue]")

        # Get the specific experiment by ID
        experiment = client.get_experiment_by_id(experiment_id)
        if not experiment:
            console.print(f"[red]Experiment '{experiment_id}' not found[/red]")
            # Return empty stats and 0 for file written when not found
            return ({"datasets": 0, "prompts": 0, "traces": 0}, 0)

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

        # Related prompts and traces are handled at the batch level
        # Only export related prompts by name (this is experiment-specific and can't be easily deduplicated)
        stats = {
            "datasets": 0,
            "datasets_skipped": 0,
            "prompts": 0,
            "prompts_skipped": 0,
            "traces": 0,
            "traces_skipped": 0,
        }
        stats["prompts"] = export_related_prompts_by_name(
            client, experiment, output_dir, force, debug, format
        )

        # Collect trace IDs from experiment items (for batch export later)
        trace_ids = [item.trace_id for item in experiment_items if item.trace_id]
        if trace_ids_collector is not None:
            trace_ids_collector.update(trace_ids)

        # Traces are exported at batch level, so we don't export them here
        stats["traces"] = 0
        stats["traces_skipped"] = 0

        if debug:
            console.print(
                f"[green]Experiment {experiment.name} exported with stats: {stats}[/green]"
            )

        # Return stats dictionary and whether file was written
        return (stats, 1 if experiment_file_written else 0)

    except Exception as e:
        console.print(f"[red]Error exporting experiment {experiment_id}: {e}[/red]")
        # Return empty stats and 0 for file written on error
        return ({"datasets": 0, "prompts": 0, "traces": 0}, 0)


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
        datasets_dir = Path(output_path) / workspace / "datasets"
        datasets_dir.mkdir(parents=True, exist_ok=True)

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

        # Collect all unique resources from all experiments first
        unique_datasets = set()
        unique_prompt_ids = set()

        # First pass: collect datasets and prompt IDs (these are available without fetching items)
        for experiment in experiments:
            if experiment.dataset_name:
                unique_datasets.add(experiment.dataset_name)
            # Get experiment data to access prompt_versions
            experiment_data = experiment.get_experiment_data()
            if experiment_data.prompt_versions:
                for prompt_version in experiment_data.prompt_versions:
                    unique_prompt_ids.add(prompt_version.prompt_id)

        # Export all unique datasets once before processing experiments
        datasets_exported = 0
        datasets_skipped = 0
        if unique_datasets:
            if len(unique_datasets) > 1:
                console.print(
                    f"[blue]Exporting {len(unique_datasets)} unique dataset(s) used by these experiments...[/blue]"
                )
            datasets_exported, datasets_skipped = export_experiment_datasets(
                client, unique_datasets, datasets_dir, format, debug, force
            )

        # Export all unique prompts once before processing experiments
        prompts_dir = output_dir.parent / "prompts"
        prompts_dir.mkdir(parents=True, exist_ok=True)
        prompts_exported = 0
        prompts_skipped = 0
        if unique_prompt_ids:
            if len(unique_prompt_ids) > 1:
                console.print(
                    f"[blue]Exporting {len(unique_prompt_ids)} unique prompt(s) used by these experiments...[/blue]"
                )
            prompts_exported, prompts_skipped = export_prompts_by_ids(
                client, unique_prompt_ids, prompts_dir, format, debug, force
            )

        # Collect all unique trace IDs from all experiments as we process them
        # We'll collect them during the first pass, then export once
        all_trace_ids: set[str] = set()

        # Export all matching experiments
        exported_count = 0
        skipped_count = 0

        # Aggregate stats from all experiments (prompts and traces already exported at batch level)
        aggregated_stats = {
            "prompts": 0,
            "prompts_skipped": 0,
        }

        for experiment in experiments:
            if debug:
                debug_print(
                    f"Exporting experiment: {experiment.name} (ID: {experiment.id})",
                    debug,
                )

            result = export_experiment_by_id(
                client,
                output_dir,
                experiment.id,
                max_traces,
                force,
                debug,
                format,
                all_trace_ids,
            )

            # result is a tuple: (stats_dict, file_written_flag)
            exp_stats, file_written = result
            # Aggregate stats (only related prompts, traces already handled)
            aggregated_stats["prompts"] += exp_stats.get("prompts", 0)
            aggregated_stats["prompts_skipped"] += exp_stats.get("prompts_skipped", 0)

            if file_written > 0:
                exported_count += 1
            else:
                skipped_count += 1

        # Export all unique traces once after collecting them from all experiments
        workspace_root = output_dir.parent
        traces_exported = 0
        traces_skipped = 0
        if all_trace_ids:
            trace_ids_list = list(all_trace_ids)
            if max_traces:
                trace_ids_list = trace_ids_list[:max_traces]
            if len(trace_ids_list) > 0:
                if len(all_trace_ids) > 1:
                    console.print(
                        f"[blue]Exporting {len(trace_ids_list)} unique trace(s) from these experiments...[/blue]"
                    )
                traces_exported, traces_skipped = export_traces_by_ids(
                    client, trace_ids_list, workspace_root, None, format, debug, force
                )

        # Collect statistics for summary
        stats = {
            "experiments": exported_count,
            "experiments_skipped": skipped_count,
            "datasets": datasets_exported,
            "datasets_skipped": datasets_skipped,
            "prompts": prompts_exported + aggregated_stats["prompts"],
            "prompts_skipped": prompts_skipped + aggregated_stats["prompts_skipped"],
            "traces": traces_exported,
            "traces_skipped": traces_skipped,
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


def export_experiment_by_name_or_id(
    name_or_id: str,
    workspace: str,
    output_path: str,
    dataset: Optional[str],
    max_traces: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
) -> None:
    """Export an experiment by name or ID.

    First tries to get the experiment by ID. If not found, tries by name.
    """
    try:
        if debug:
            debug_print(f"Attempting to export experiment: {name_or_id}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "experiments"
        output_dir.mkdir(parents=True, exist_ok=True)
        datasets_dir = Path(output_path) / workspace / "datasets"
        datasets_dir.mkdir(parents=True, exist_ok=True)

        # Try to get experiment by ID first
        try:
            if debug:
                debug_print(f"Trying to get experiment by ID: {name_or_id}", debug)
            experiment = client.get_experiment_by_id(name_or_id)

            # Successfully found by ID, export it
            if debug:
                debug_print(
                    f"Found experiment by ID: {experiment.name} (ID: {experiment.id})",
                    debug,
                )

            # Collect trace IDs as we export
            trace_ids_collector: set[str] = set()

            # Use the ID-based export function
            result = export_experiment_by_id(
                client,
                output_dir,
                name_or_id,
                max_traces,
                force,
                debug,
                format,
                trace_ids_collector,
            )

            exp_stats, file_written = result

            # Export related datasets
            unique_datasets = set()
            if experiment.dataset_name:
                unique_datasets.add(experiment.dataset_name)

            datasets_exported = 0
            datasets_skipped = 0
            if unique_datasets:
                datasets_exported, datasets_skipped = export_experiment_datasets(
                    client, unique_datasets, datasets_dir, format, debug, force
                )

            # Export traces collected from experiment items
            workspace_root = output_dir.parent
            traces_exported = 0
            traces_skipped = 0
            if trace_ids_collector:
                trace_ids_list = list(trace_ids_collector)
                if max_traces:
                    trace_ids_list = trace_ids_list[:max_traces]
                if len(trace_ids_list) > 0:
                    traces_exported, traces_skipped = export_traces_by_ids(
                        client,
                        trace_ids_list,
                        workspace_root,
                        None,
                        format,
                        debug,
                        force,
                    )

            # Collect statistics for summary
            stats = {
                "experiments": 1 if file_written > 0 else 0,
                "experiments_skipped": 0 if file_written > 0 else 1,
                "datasets": datasets_exported,
                "datasets_skipped": datasets_skipped,
                "prompts": exp_stats.get("prompts", 0),
                "prompts_skipped": exp_stats.get("prompts_skipped", 0),
                "traces": traces_exported,
                "traces_skipped": traces_skipped,
            }

            # Show export summary
            print_export_summary(stats, format)

            if file_written > 0:
                console.print(
                    f"[green]Successfully exported experiment '{experiment.name}' (ID: {experiment.id}) to {output_dir}[/green]"
                )
            else:
                console.print(
                    f"[yellow]Experiment '{experiment.name}' (ID: {experiment.id}) already exists (use --force to re-download)[/yellow]"
                )
            return

        except exceptions.ExperimentNotFound:
            # Not found by ID, try by name
            if debug:
                debug_print(
                    f"Experiment not found by ID, trying by name: {name_or_id}", debug
                )
            # Fall through to name-based export
            pass

        # Try by name (either because ID lookup failed or we're explicitly trying name)
        export_experiment_by_name(
            name_or_id,
            workspace,
            output_path,
            dataset,
            max_traces,
            force,
            debug,
            format,
            api_key,
        )

    except Exception as e:
        console.print(f"[red]Error exporting experiment: {e}[/red]")
        sys.exit(1)


@click.command(name="experiment")
@click.argument("name_or_id", type=str)
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
    name_or_id: str,
    dataset: Optional[str],
    max_traces: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export an experiment by exact name to workspace/experiments.

    The command will first try to find the experiment by ID. If not found, it will try by name.
    """
    # Get workspace and API key from context
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_experiment_by_name_or_id(
        name_or_id, workspace, path, dataset, max_traces, force, debug, format, api_key
    )
