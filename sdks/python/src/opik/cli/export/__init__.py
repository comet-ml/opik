"""Download command for Opik CLI."""

import sys
from pathlib import Path
from typing import List, Optional

import click
from rich.console import Console

import opik
from .dataset import export_dataset_command, export_all_datasets
from .experiment import export_experiment_command, export_all_experiments
from .prompt import export_prompt_command, export_all_prompts
from .project import export_project_command, export_all_projects
from .utils import debug_print

console = Console()


@click.group(name="export", invoke_without_command=True)
@click.argument("workspace", type=str)
@click.pass_context
def export_group(ctx: click.Context, workspace: str) -> None:
    """Export data from Opik workspace."""
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace


def _run_bulk_export(
    workspace: str,
    output_path: str,
    max_results: int,
    filter_string: Optional[str],
    include_types: List[str],
    exclude_types: List[str],
    all_types: bool,
    name_pattern: Optional[str],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Run bulk export for multiple data types."""
    try:
        if debug:
            debug_print(f"Starting bulk export for workspace: {workspace}", debug)
            debug_print(f"Output path: {output_path}", debug)
            debug_print(f"Max results: {max_results}", debug)
            debug_print(f"Filter: {filter_string}", debug)
            debug_print(f"Include types: {include_types}", debug)
            debug_print(f"Exclude types: {exclude_types}", debug)
            debug_print(f"All types: {all_types}", debug)
            debug_print(f"Name pattern: {name_pattern}", debug)
            debug_print(f"Force: {force}", debug)
            debug_print(f"Format: {format}", debug)

        # Determine which types to export
        if all_types:
            types_to_export = [
                "datasets",
                "prompts",
                "projects",
                "experiments",
                "traces",
            ]
        elif include_types:
            types_to_export = list(include_types)
        else:
            # Default to traces only if no specific types are specified
            types_to_export = ["traces"]

        # Remove excluded types
        types_to_export = [t for t in types_to_export if t not in exclude_types]

        if debug:
            debug_print(f"Types to export: {types_to_export}", debug)

        # Initialize client
        client = opik.Opik(workspace=workspace)

        # Create base output directory
        output_dir = Path(output_path) / workspace
        output_dir.mkdir(parents=True, exist_ok=True)

        total_stats = {
            "datasets": 0,
            "prompts": 0,
            "projects": 0,
            "experiments": 0,
            "traces": 0,
        }

        # Export each type
        for export_type in types_to_export:
            if debug:
                debug_print(f"Exporting {export_type}...", debug)

            try:
                if export_type == "datasets":
                    stats = export_all_datasets(
                        client,
                        output_dir,
                        max_results,
                        force,
                        debug,
                        format,
                        name_pattern,
                    )
                    total_stats["datasets"] = stats.get("datasets", 0)
                elif export_type == "prompts":
                    stats = export_all_prompts(
                        client,
                        output_dir,
                        max_results,
                        force,
                        debug,
                        format,
                        name_pattern,
                    )
                    total_stats["prompts"] = stats.get("prompts", 0)
                elif export_type == "projects":
                    stats = export_all_projects(
                        client,
                        output_dir,
                        max_results,
                        filter_string,
                        force,
                        debug,
                        format,
                        name_pattern,
                    )
                    total_stats["projects"] = stats.get("projects", 0)
                    total_stats["traces"] = stats.get("traces", 0)
                elif export_type == "experiments":
                    stats = export_all_experiments(
                        client,
                        output_dir,
                        max_results,
                        force,
                        debug,
                        format,
                        name_pattern,
                    )
                    total_stats["experiments"] = stats.get("experiments", 0)
                elif export_type == "traces":
                    # For traces, we need to get all projects first
                    projects_response = client.rest_client.projects.find_projects()
                    projects = projects_response.content or []

                    for project in projects:
                        if name_pattern and not _matches_name_pattern(
                            project.name, name_pattern
                        ):
                            continue

                        project_dir = output_dir / "projects" / project.name
                        project_dir.mkdir(parents=True, exist_ok=True)

                        stats = export_all_projects(
                            client,
                            output_dir,
                            max_results,
                            filter_string,
                            force,
                            debug,
                            format,
                            name_pattern,
                        )
                        total_stats["traces"] += stats.get("traces", 0)

                if debug:
                    debug_print(
                        f"Exported {export_type}: {total_stats[export_type]} items",
                        debug,
                    )

            except Exception as e:
                console.print(f"[red]Error exporting {export_type}: {e}[/red]")
                if debug:
                    debug_print(f"Error details: {e}", debug)
                continue

        # Print summary
        console.print("\n[green]Export Summary:[/green]")
        for export_type, count in total_stats.items():
            if count > 0:
                console.print(f"  {export_type.capitalize()}: {count} items")

        console.print("\n[green]Export completed successfully![/green]")
        console.print(f"Data saved to: {output_dir}")

    except Exception as e:
        console.print(f"[red]Error during bulk export: {e}[/red]")
        if debug:
            debug_print(f"Error details: {e}", debug)
        sys.exit(1)


def _matches_name_pattern(name: str, pattern: str) -> bool:
    """Check if a name matches the given regex pattern."""
    import re

    try:
        return bool(re.search(pattern, name))
    except re.error:
        return False


# Create bulk export command
@click.command(name="all")
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, writable=True),
    default="./",
    help="Directory to save exported data. Defaults to current directory.",
)
@click.option(
    "--max-results",
    type=int,
    default=1000,
    help="Maximum number of items to export per data type. Defaults to 1000.",
)
@click.option(
    "--filter",
    type=str,
    help="Filter string to narrow down traces using Opik Query Language (OQL).",
)
@click.option(
    "--include",
    type=click.Choice(["datasets", "prompts", "projects", "experiments", "traces"]),
    multiple=True,
    help="Data types to include. Can be specified multiple times.",
)
@click.option(
    "--exclude",
    type=click.Choice(["datasets", "prompts", "projects", "experiments", "traces"]),
    multiple=True,
    help="Data types to exclude. Can be specified multiple times.",
)
@click.option(
    "--all-types",
    is_flag=True,
    help="Include all data types (datasets, prompts, projects, experiments, traces).",
)
@click.option(
    "--name",
    type=str,
    help="Filter items by name using Python regex patterns.",
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
def export_all_command(
    ctx: click.Context,
    path: str,
    max_results: int,
    filter: Optional[str],
    include: tuple,
    exclude: tuple,
    all_types: bool,
    name: Optional[str],
    force: bool,
    debug: bool,
    format: str,
) -> None:
    """Export all data types from workspace."""
    workspace = ctx.obj["workspace"]
    _run_bulk_export(
        workspace=workspace,
        output_path=path,
        max_results=max_results,
        filter_string=filter,
        include_types=list(include),
        exclude_types=list(exclude),
        all_types=all_types,
        name_pattern=name,
        force=force,
        debug=debug,
        format=format,
    )


# Add the subcommands
export_group.add_command(export_all_command)
export_group.add_command(export_dataset_command)
export_group.add_command(export_experiment_command)
export_group.add_command(export_prompt_command)
export_group.add_command(export_project_command)
