"""Import command for Opik CLI."""

import sys
from pathlib import Path
from typing import Optional

import click
from rich.console import Console

import opik

from .dataset import import_datasets_from_directory
from .experiment import import_experiments_from_directory
from .project import import_projects_from_directory
from .prompt import import_prompts_from_directory

console = Console()

IMPORT_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


def _import_by_type(
    import_type: str,
    workspace_folder: str,
    workspace: str,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments: bool = False,
    api_key: Optional[str] = None,
) -> None:
    """
    Import data by type (dataset, project, experiment) with pattern matching.

    Args:
        import_type: Type of data to import ("dataset", "project", "experiment")
        workspace_folder: Base workspace folder containing the data
        workspace: Target workspace name
        dry_run: Whether to show what would be imported without importing
        name_pattern: Optional string pattern to filter items by name (case-insensitive substring matching)
        debug: Enable debug output
        recreate_experiments: Whether to recreate experiments after importing
    """
    try:
        if debug:
            console.print(
                f"[blue]DEBUG: Starting {import_type} import from {workspace_folder}[/blue]"
            )

        # Initialize Opik client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Determine source directory based on import type
        base_path = Path(workspace_folder)

        if import_type == "dataset":
            source_dir = base_path / "datasets"
        elif import_type == "project":
            source_dir = base_path / "projects"
        elif import_type == "experiment":
            source_dir = base_path / "experiments"
        elif import_type == "prompt":
            source_dir = base_path / "prompts"
        else:
            console.print(f"[red]Unknown import type: {import_type}[/red]")
            return

        if not source_dir.exists():
            console.print(f"[red]Source directory {source_dir} does not exist[/red]")
            sys.exit(1)

        if debug:
            console.print(f"[blue]Source directory: {source_dir}[/blue]")

        imported_count = 0

        if import_type == "dataset":
            imported_count = import_datasets_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )
        elif import_type == "project":
            imported_count = import_projects_from_directory(
                client, source_dir, dry_run, name_pattern, debug, recreate_experiments
            )
        elif import_type == "experiment":
            imported_count = import_experiments_from_directory(
                client, source_dir, dry_run, name_pattern, debug, recreate_experiments
            )
        elif import_type == "prompt":
            imported_count = import_prompts_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )

        if dry_run:
            console.print(
                f"[blue]Dry run complete: Would import {imported_count} {import_type}s[/blue]"
            )
        else:
            if imported_count < 0:
                # Negative count indicates errors occurred
                console.print(
                    f"[red]Import failed: Errors occurred while importing {import_type}s[/red]"
                )
            elif imported_count == 0:
                console.print(f"[yellow]No {import_type}s were imported[/yellow]")
            else:
                console.print(
                    f"[green]Successfully imported {imported_count} {import_type}s[/green]"
                )

    except Exception as e:
        console.print(f"[red]Error importing {import_type}s: {e}[/red]")
        sys.exit(1)


@click.group(name="import", context_settings=IMPORT_CONTEXT_SETTINGS)
@click.argument("workspace", type=str)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def import_group(ctx: click.Context, workspace: str, api_key: Optional[str]) -> None:
    """Import data to Opik workspace.

    This command allows you to import previously exported data back into an Opik workspace.
    Supported data types include projects, datasets, experiments, and prompts.

    \b
    General Usage:
        opik import WORKSPACE TYPE FOLDER/ [OPTIONS]

    \b
    Data Types:
        project     Import projects from workspace_folder/projects/
        dataset     Import datasets from workspace_folder/datasets/
        experiment  Import experiments from workspace_folder/experiments/
        prompt      Import prompts from workspace_folder/prompts/

    \b
    Common Options:
        --dry-run   Preview what would be imported without actually importing
        --name      Filter items by name using pattern matching
        --debug     Show detailed information about the import process

    \b
    Examples:
        # Preview all projects that would be imported
        opik import my-workspace project ./exported-data/ --dry-run

        # Import specific projects
        opik import my-workspace project ./exported-data/ --name "my-project"

        # Import all datasets
        opik import my-workspace dataset ./exported-data/
    """
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace
    # Use API key from this command or from parent context
    ctx.obj["api_key"] = api_key or (
        ctx.parent.obj.get("api_key") if ctx.parent and ctx.parent.obj else None
    )


# Set subcommand metavar to ITEM instead of COMMAND
import_group.subcommand_metavar = "ITEM [ARGS]..."


def format_commands(
    self: click.Group, ctx: click.Context, formatter: click.HelpFormatter
) -> None:
    """Override to change 'Commands' heading to 'Items'."""
    commands = []
    for subcommand in self.list_commands(ctx):
        cmd = self.get_command(ctx, subcommand)
        if cmd is None or cmd.hidden:
            continue
        commands.append((subcommand, cmd))

    if len(commands):
        limit = formatter.width - 6 - max(len(cmd[0]) for cmd in commands)
        rows = []
        for subcommand, cmd in commands:
            help = cmd.get_short_help_str(limit)
            rows.append((subcommand, help))

        if rows:
            with formatter.section("Items"):
                formatter.write_dl(rows)


# Override format_commands method
import_group.format_commands = format_commands.__get__(import_group, type(import_group))


@import_group.command(name="dataset")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing. Use this to preview datasets before importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter datasets by name using case-insensitive substring matching. Use this to import only specific datasets.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_dataset(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import datasets from workspace/datasets directory.

    This command imports all datasets found in the workspace_folder/datasets/ directory.
    By default, ALL datasets in the directory will be imported. Use --name to filter
    specific datasets and --dry-run to preview what will be imported.

    \b
    Examples:
    \b
        # Preview all datasets that would be imported
        opik import my-workspace dataset ./exported-data/ --dry-run
    \b
        # Import all datasets
        opik import my-workspace dataset ./exported-data/
    \b
        # Import only datasets containing "training" in the name
        opik import my-workspace dataset ./exported-data/ --name "training"
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "dataset", workspace_folder, workspace, dry_run, name, debug, api_key=api_key
    )


@import_group.command(name="project")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing. Use this to preview projects before importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter projects by name using case-insensitive substring matching. Use this to import only specific projects.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_project(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import projects from workspace/projects directory.

    This command imports all projects found in the workspace_folder/projects/ directory.
    By default, ALL projects in the directory will be imported. Use --name to filter
    specific projects and --dry-run to preview what will be imported.

    \b
    Examples:
    \b
        # Preview all projects that would be imported
        opik import my-workspace project ./exported-data/ --dry-run
    \b
        # Import all projects
        opik import my-workspace project ./exported-data/
    \b
        # Import only projects containing "my-project" in the name
        opik import my-workspace project ./exported-data/ --name "my-project"
    \b
        # Import projects with debug output
        opik import my-workspace project ./exported-data/ --debug
    \b
        # Preview specific projects before importing
        opik import my-workspace project ./exported-data/ --name "test" --dry-run
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "project",
        workspace_folder,
        workspace,
        dry_run,
        name,
        debug,
        True,  # Always recreate experiments when importing projects
        api_key=api_key,
    )


@import_group.command(name="experiment")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter experiments by name using string pattern matching (case-insensitive).",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_experiment(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import experiments from workspace/experiments directory."""
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    # Always recreate experiments when importing
    _import_by_type(
        "experiment",
        workspace_folder,
        workspace,
        dry_run,
        name,
        debug,
        True,
        api_key=api_key,
    )


@import_group.command(name="prompt")
@click.argument(
    "workspace_folder", type=click.Path(file_okay=False, dir_okay=True, readable=True)
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--name",
    type=str,
    help="Filter prompts by name using string pattern matching (case-insensitive).",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_prompt(
    ctx: click.Context,
    workspace_folder: str,
    dry_run: bool,
    name: Optional[str],
    debug: bool,
) -> None:
    """Import prompts from workspace/prompts directory."""
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "prompt", workspace_folder, workspace, dry_run, name, debug, api_key=api_key
    )
