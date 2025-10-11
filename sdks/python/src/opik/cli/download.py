"""Download command for Opik CLI."""

import json
import re
import sys
from datetime import datetime
from pathlib import Path
from typing import Any, Optional

import click
from rich.console import Console
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik

console = Console()


def _matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given regex pattern."""
    if pattern is None:
        return True
    try:
        return bool(re.search(pattern, name))
    except re.error as e:
        console.print(f"[red]Invalid regex pattern '{pattern}': {e}[/red]")
        return False


def _download_traces(
    client: Any,
    project_name: str,
    project_dir: Path,
    max_results: int,
    filter: Optional[str],
    name_pattern: Optional[str] = None,
) -> int:
    """Download traces and their spans."""
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        console=console,
    ) as progress:
        task = progress.add_task("Searching for traces...", total=None)

        traces = client.search_traces(
            project_name=project_name,
            filter_string=filter,
            max_results=max_results,
            truncate=False,  # Don't truncate data for download
        )

        progress.update(task, description=f"Found {len(traces)} traces")

    if not traces:
        console.print("[yellow]No traces found in the project.[/yellow]")
        return 0

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
                f"[blue]Filtered to {len(traces)} traces matching pattern '{name_pattern}'[/blue]"
            )

    if not traces:
        console.print("[yellow]No traces found matching the name pattern.[/yellow]")
        return 0

    # Download each trace with its spans
    downloaded_count = 0
    with Progress(
        SpinnerColumn(),
        TextColumn("[progress.description]{task.description}"),
        console=console,
    ) as progress:
        task = progress.add_task("Downloading traces...", total=len(traces))

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

                # Save to file
                trace_file = project_dir / f"trace_{trace.id}.json"
                with open(trace_file, "w", encoding="utf-8") as f:
                    json.dump(trace_data, f, indent=2, default=str)

                downloaded_count += 1
                progress.update(
                    task,
                    description=f"Downloaded {downloaded_count}/{len(traces)} traces",
                )

            except Exception as e:
                console.print(f"[red]Error downloading trace {trace.id}: {e}[/red]")
                continue

    return downloaded_count


def _download_datasets(
    client: Any, project_dir: Path, max_results: int, name_pattern: Optional[str] = None
) -> int:
    """Download datasets."""
    try:
        datasets = client.get_datasets(max_results=max_results, sync_items=True)

        if not datasets:
            console.print("[yellow]No datasets found in the project.[/yellow]")
            return 0

        # Filter datasets by name pattern if specified
        if name_pattern:
            original_count = len(datasets)
            datasets = [
                dataset
                for dataset in datasets
                if _matches_name_pattern(dataset.name, name_pattern)
            ]
            if len(datasets) < original_count:
                console.print(
                    f"[blue]Filtered to {len(datasets)} datasets matching pattern '{name_pattern}'[/blue]"
                )

        if not datasets:
            console.print(
                "[yellow]No datasets found matching the name pattern.[/yellow]"
            )
            return 0

        downloaded_count = 0
        for dataset in datasets:
            try:
                # Get dataset items
                dataset_items = []
                # Note: Dataset iteration is not directly supported, so we'll get basic info
                dataset_items.append(
                    {
                        "name": dataset.name,
                        "description": dataset.description,
                        "note": "Dataset items not downloaded - dataset iteration not supported",
                    }
                )

                # Create dataset data structure
                dataset_data = {
                    "name": dataset.name,
                    "description": dataset.description,
                    "items": dataset_items,
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save to file
                dataset_file = project_dir / f"dataset_{dataset.name}.json"
                with open(dataset_file, "w", encoding="utf-8") as f:
                    json.dump(dataset_data, f, indent=2, default=str)

                downloaded_count += 1

            except Exception as e:
                console.print(
                    f"[red]Error downloading dataset {dataset.name}: {e}[/red]"
                )
                continue

        return downloaded_count

    except Exception as e:
        console.print(f"[red]Error downloading datasets: {e}[/red]")
        return 0


def _download_experiments(
    client: Any, project_dir: Path, max_results: int, name_pattern: Optional[str] = None
) -> int:
    """Download experiments."""
    try:
        # Get all datasets first to find experiments
        datasets = client.get_datasets(max_results=100, sync_items=False)

        if not datasets:
            console.print(
                "[yellow]No datasets found to check for experiments.[/yellow]"
            )
            return 0

        downloaded_count = 0
        for dataset in datasets:
            try:
                experiments = client.get_dataset_experiments(
                    dataset.name, max_results=max_results
                )

                for experiment in experiments:
                    # Filter by name pattern if specified
                    if name_pattern and not _matches_name_pattern(
                        experiment.name, name_pattern
                    ):
                        continue

                    try:
                        # Get experiment data
                        experiment_data_obj = experiment.get_experiment_data()

                        # Create experiment data structure
                        experiment_data = {
                            "id": experiment.id,
                            "name": experiment.name,
                            "dataset_name": experiment.dataset_name,
                            "experiment_data": experiment_data_obj.model_dump(),
                            "downloaded_at": datetime.now().isoformat(),
                        }

                        # Save to file
                        experiment_file = (
                            project_dir / f"experiment_{experiment.id}.json"
                        )
                        with open(experiment_file, "w", encoding="utf-8") as f:
                            json.dump(experiment_data, f, indent=2, default=str)

                        downloaded_count += 1

                    except Exception as e:
                        console.print(
                            f"[red]Error downloading experiment {experiment.id}: {e}[/red]"
                        )
                        continue

            except Exception as e:
                console.print(
                    f"[red]Error getting experiments for dataset {dataset.name}: {e}[/red]"
                )
                continue

        return downloaded_count

    except Exception as e:
        console.print(f"[red]Error downloading experiments: {e}[/red]")
        return 0


def _download_prompts(
    client: Any, project_dir: Path, max_results: int, name_pattern: Optional[str] = None
) -> int:
    """Download prompts."""
    try:
        prompts = client.search_prompts()

        if not prompts:
            console.print("[yellow]No prompts found in the project.[/yellow]")
            return 0

        # Filter prompts by name pattern if specified
        if name_pattern:
            original_count = len(prompts)
            prompts = [
                prompt
                for prompt in prompts
                if _matches_name_pattern(prompt.name, name_pattern)
            ]
            if len(prompts) < original_count:
                console.print(
                    f"[blue]Filtered to {len(prompts)} prompts matching pattern '{name_pattern}'[/blue]"
                )

        if not prompts:
            console.print(
                "[yellow]No prompts found matching the name pattern.[/yellow]"
            )
            return 0

        downloaded_count = 0
        for prompt in prompts:
            try:
                # Get prompt history
                prompt_history = client.get_prompt_history(prompt.name)

                # Create prompt data structure
                prompt_data = {
                    "name": prompt.name,
                    "current_version": prompt.model_dump(),
                    "history": [version.model_dump() for version in prompt_history],
                    "downloaded_at": datetime.now().isoformat(),
                }

                # Save to file
                prompt_file = (
                    project_dir / f"prompt_{prompt.name.replace('/', '_')}.json"
                )
                with open(prompt_file, "w", encoding="utf-8") as f:
                    json.dump(prompt_data, f, indent=2, default=str)

                downloaded_count += 1

            except Exception as e:
                console.print(f"[red]Error downloading prompt {prompt.name}: {e}[/red]")
                continue

        return downloaded_count

    except Exception as e:
        console.print(f"[red]Error downloading prompts: {e}[/red]")
        return 0


@click.command()
@click.argument("workspace_or_project", type=str)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save downloaded data. Defaults to current directory.",
)
@click.option(
    "--max-results",
    type=int,
    default=1000,
    help="Maximum number of items to download per data type. Defaults to 1000.",
)
@click.option(
    "--filter",
    type=str,
    help="Filter string to narrow down the search using Opik Query Language (OQL).",
)
@click.option(
    "--all",
    is_flag=True,
    help="Include all data types (traces, datasets, experiments, prompts).",
)
@click.option(
    "--include",
    type=click.Choice(
        ["traces", "datasets", "experiments", "prompts"], case_sensitive=False
    ),
    multiple=True,
    default=["traces"],
    help="Data types to include in download. Can be specified multiple times. Defaults to traces only.",
)
@click.option(
    "--exclude",
    type=click.Choice(
        ["traces", "datasets", "experiments", "prompts"], case_sensitive=False
    ),
    multiple=True,
    help="Data types to exclude from download. Can be specified multiple times.",
)
@click.option(
    "--name",
    type=str,
    help="Filter items by name using Python regex patterns. Matches against trace names, dataset names, experiment names, or prompt names.",
)
def download(
    workspace_or_project: str,
    path: str,
    max_results: int,
    filter: Optional[str],
    all: bool,
    include: tuple,
    exclude: tuple,
    name: Optional[str],
) -> None:
    """
    Download data from a workspace or workspace/project to local files.

    This command fetches traces, datasets, experiments, and prompts from the specified workspace or project
    and saves them to local JSON files in the output directory.

    Note: Thread metadata is automatically derived from traces with the same thread_id,
    so threads don't need to be downloaded separately.

    WORKSPACE_OR_PROJECT: Either a workspace name (e.g., "my-workspace") to download all projects,
                          or workspace/project (e.g., "my-workspace/my-project") to download a specific project.
    """
    try:
        # Parse workspace/project from the argument
        if "/" in workspace_or_project:
            workspace, project_name = workspace_or_project.split("/", 1)
            download_specific_project = True
        else:
            # Only workspace specified - download all projects
            workspace = workspace_or_project
            project_name = None
            download_specific_project = False

        # Initialize Opik client with workspace
        client = opik.Opik(workspace=workspace)

        # Create output directory
        output_path = Path(path)
        output_path.mkdir(parents=True, exist_ok=True)

        # Determine which data types to download
        if all:
            # If --all is specified, include all data types
            include_set = {"traces", "datasets", "experiments", "prompts"}
        else:
            include_set = set(item.lower() for item in include)

        exclude_set = set(item.lower() for item in exclude)

        # Apply exclusions
        data_types = include_set - exclude_set

        if download_specific_project:
            # Download from specific project
            console.print(
                f"[green]Downloading data from workspace: {workspace}, project: {project_name}[/green]"
            )

            # Create workspace/project directory structure
            workspace_dir = output_path / workspace
            assert project_name is not None  # Type narrowing for mypy
            project_dir = workspace_dir / project_name
            project_dir.mkdir(parents=True, exist_ok=True)

            console.print(f"[blue]Output directory: {workspace}/{project_name}[/blue]")
            console.print(f"[blue]Data types: {', '.join(sorted(data_types))}[/blue]")

            # Note about workspace vs project-specific data
            project_specific = [dt for dt in data_types if dt in ["traces"]]
            workspace_data = [
                dt for dt in data_types if dt in ["datasets", "experiments", "prompts"]
            ]

            if project_specific and workspace_data:
                console.print(
                    f"[yellow]Note: {', '.join(project_specific)} are project-specific, {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )
            elif workspace_data:
                console.print(
                    f"[yellow]Note: {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                )

            # Download each data type
            total_downloaded = 0

            # Download traces
            if "traces" in data_types:
                console.print("[blue]Downloading traces...[/blue]")
                traces_downloaded = _download_traces(
                    client, project_name, project_dir, max_results, filter, name
                )
                total_downloaded += traces_downloaded

            # Download datasets
            if "datasets" in data_types:
                console.print("[blue]Downloading datasets...[/blue]")
                datasets_downloaded = _download_datasets(
                    client, project_dir, max_results, name
                )
                total_downloaded += datasets_downloaded

            # Download experiments
            if "experiments" in data_types:
                console.print("[blue]Downloading experiments...[/blue]")
                experiments_downloaded = _download_experiments(
                    client, project_dir, max_results, name
                )
                total_downloaded += experiments_downloaded

            # Download prompts
            if "prompts" in data_types:
                console.print("[blue]Downloading prompts...[/blue]")
                prompts_downloaded = _download_prompts(
                    client, project_dir, max_results, name
                )
                total_downloaded += prompts_downloaded

            console.print(
                f"[green]Successfully downloaded {total_downloaded} items to {project_dir}[/green]"
            )
        else:
            # Download from all projects in workspace
            console.print(
                f"[green]Downloading data from workspace: {workspace} (all projects)[/green]"
            )

            # Get all projects in the workspace
            try:
                projects_response = client.rest_client.projects.find_projects()
                projects = projects_response.content or []

                if not projects:
                    console.print(
                        f"[yellow]No projects found in workspace '{workspace}'[/yellow]"
                    )
                    return

                console.print(
                    f"[blue]Found {len(projects)} projects in workspace[/blue]"
                )
                console.print(
                    f"[blue]Data types: {', '.join(sorted(data_types))}[/blue]"
                )

                # Note about workspace vs project-specific data
                project_specific = [dt for dt in data_types if dt in ["traces"]]
                workspace_data = [
                    dt
                    for dt in data_types
                    if dt in ["datasets", "experiments", "prompts"]
                ]

                if project_specific and workspace_data:
                    console.print(
                        f"[yellow]Note: {', '.join(project_specific)} are project-specific, {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                    )
                elif workspace_data:
                    console.print(
                        f"[yellow]Note: {', '.join(workspace_data)} belong to workspace '{workspace}'[/yellow]"
                    )

                total_downloaded = 0

                # Download workspace-level data once (datasets, experiments, prompts)
                workspace_dir = output_path / workspace
                workspace_dir.mkdir(parents=True, exist_ok=True)

                # Download datasets
                if "datasets" in data_types:
                    console.print("[blue]Downloading datasets...[/blue]")
                    datasets_downloaded = _download_datasets(
                        client, workspace_dir, max_results, name
                    )
                    total_downloaded += datasets_downloaded

                # Download experiments
                if "experiments" in data_types:
                    console.print("[blue]Downloading experiments...[/blue]")
                    experiments_downloaded = _download_experiments(
                        client, workspace_dir, max_results, name
                    )
                    total_downloaded += experiments_downloaded

                # Download prompts
                if "prompts" in data_types:
                    console.print("[blue]Downloading prompts...[/blue]")
                    prompts_downloaded = _download_prompts(
                        client, workspace_dir, max_results, name
                    )
                    total_downloaded += prompts_downloaded

                # Download traces from each project
                if "traces" in data_types:
                    for project in projects:
                        project_name = project.name
                        console.print(
                            f"[blue]Downloading traces from project: {project_name}...[/blue]"
                        )

                        # Create project directory
                        assert project_name is not None  # Type narrowing for mypy
                        project_dir = workspace_dir / project_name
                        project_dir.mkdir(parents=True, exist_ok=True)

                        traces_downloaded = _download_traces(
                            client, project_name, project_dir, max_results, filter, name
                        )
                        total_downloaded += traces_downloaded

                        if traces_downloaded > 0:
                            console.print(
                                f"[green]Downloaded {traces_downloaded} traces from {project_name}[/green]"
                            )

                console.print(
                    f"[green]Successfully downloaded {total_downloaded} items from workspace '{workspace}'[/green]"
                )

            except Exception as e:
                console.print(f"[red]Error getting projects from workspace: {e}[/red]")
                sys.exit(1)

    except Exception as e:
        console.print(f"[red]Error: {e}[/red]")
        sys.exit(1)
