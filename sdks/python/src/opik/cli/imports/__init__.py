"""Import command for Opik CLI."""

import sys
from pathlib import Path
from typing import Dict, Optional

import click
from rich.console import Console

import opik

from .dataset import import_datasets_from_directory
from .experiment import import_experiments_from_directory
from .project import import_projects_from_directory
from .prompt import import_prompts_from_directory
from .utils import print_import_summary, debug_print

console = Console()

IMPORT_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


def _import_by_type(
    import_type: str,
    path: str,
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
        path: Base directory containing the exported data
        workspace: Target workspace name
        dry_run: Whether to show what would be imported without importing
        name_pattern: Optional string pattern to filter items by name (case-insensitive substring matching)
        debug: Enable debug output
        recreate_experiments: Whether to recreate experiments after importing
    """
    try:
        debug_print(f"DEBUG: Starting {import_type} import from {path}", debug)

        # Initialize Opik client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Determine source directory based on import type
        base_path = Path(path)

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

        debug_print(f"Source directory: {source_dir}", debug)

        stats: Dict[str, int] = {}

        if import_type == "dataset":
            stats = import_datasets_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )
        elif import_type == "project":
            stats = import_projects_from_directory(
                client, source_dir, dry_run, name_pattern, debug, recreate_experiments
            )
        elif import_type == "experiment":
            stats = import_experiments_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )
        elif import_type == "prompt":
            stats = import_prompts_from_directory(
                client, source_dir, dry_run, name_pattern, debug
            )

        # Display summary
        print_import_summary(stats)

        # Also show a simple message for backward compatibility
        # Map import_type to the key used in stats dictionary
        type_key_map = {
            "dataset": "datasets",
            "prompt": "prompts",
            "project": "projects",
            "experiment": "experiments",
        }
        stats_key = type_key_map.get(import_type, import_type + "s")
        imported_count = stats.get(stats_key, 0)
        errors = stats.get(stats_key + "_errors", 0)

        if dry_run:
            console.print(
                f"[blue]Dry run complete: Would import {imported_count} {import_type}s[/blue]"
            )
        else:
            if errors > 0:
                console.print(
                    f"[yellow]Import completed with {errors} error(s) while importing {import_type}s[/yellow]"
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
        opik import WORKSPACE TYPE NAME [OPTIONS]

    \b
    Data Types:
        project     Import projects from path/projects/ (default: opik_exports)
        dataset     Import datasets from path/datasets/ (default: opik_exports)
        experiment  Import experiments from path/experiments/ (default: opik_exports)
        prompt      Import prompts from path/prompts/ (default: opik_exports)

    \b
    Common Options:
        --path, -p  Directory containing exported data (default: opik_exports)
        --dry-run   Preview what would be imported without actually importing
        --debug     Show detailed information about the import process

    \b
    Examples:
        # Preview an experiment that would be imported
        opik import my-workspace experiment "my-experiment" --dry-run

        # Import a specific project
        opik import my-workspace project "my-project"

        # Import a specific dataset
        opik import my-workspace dataset "my-dataset"

        # Import from a custom path
        opik import my-workspace project "my-project" --path ./custom-exports/
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
setattr(
    import_group,
    "format_commands",
    format_commands.__get__(import_group, type(import_group)),
)


@import_group.command(name="dataset")
@click.argument("name", type=str)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, readable=True),
    default="opik_exports",
    help="Directory containing exported data. Defaults to opik_exports.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing. Use this to preview datasets before importing.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_dataset(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    debug: bool,
) -> None:
    """Import datasets from workspace/datasets directory.

    This command imports datasets matching the specified name from the path/datasets/ directory.
    The name is matched using case-insensitive substring matching.

    \b
    Examples:
    \b
        # Preview a dataset that would be imported
        opik import my-workspace dataset "my-dataset" --dry-run
    \b
        # Import a specific dataset
        opik import my-workspace dataset "my-dataset"
    \b
        # Import datasets containing "training" in the name
        opik import my-workspace dataset "training"
    \b
        # Import from a custom path
        opik import my-workspace dataset "my-dataset" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type("dataset", path, workspace, dry_run, name, debug, api_key=api_key)


@import_group.command(name="project")
@click.argument("name", type=str)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, readable=True),
    default="opik_exports",
    help="Directory containing exported data. Defaults to opik_exports.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing. Use this to preview projects before importing.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_project(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    debug: bool,
) -> None:
    """Import projects from workspace/projects directory.

    This command imports projects matching the specified name from the path/projects/ directory.
    The name is matched using case-insensitive substring matching.

    \b
    Examples:
    \b
        # Preview a project that would be imported
        opik import my-workspace project "my-project" --dry-run
    \b
        # Import a specific project
        opik import my-workspace project "my-project"
    \b
        # Import projects containing "test" in the name
        opik import my-workspace project "test"
    \b
        # Import projects with debug output
        opik import my-workspace project "my-project" --debug
    \b
        # Import from a custom path
        opik import my-workspace project "my-project" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "project",
        path,
        workspace,
        dry_run,
        name,
        debug,
        True,  # Always recreate experiments when importing projects
        api_key=api_key,
    )


@import_group.command(name="experiment")
@click.argument("name", type=str)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, readable=True),
    default="opik_exports",
    help="Directory containing exported data. Defaults to opik_exports.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_experiment(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    debug: bool,
) -> None:
    """Import experiments from workspace/experiments directory.

    This command imports experiments matching the specified name from the path/experiments/ directory.
    The name is matched using case-insensitive substring matching.

    \b
    Examples:
    \b
        # Preview an experiment that would be imported
        opik import my-workspace experiment "my-experiment" --dry-run
    \b
        # Import a specific experiment
        opik import my-workspace experiment "my-experiment"
    \b
        # Import from a custom path
        opik import my-workspace experiment "my-experiment" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    # Always recreate experiments when importing
    _import_by_type(
        "experiment",
        path,
        workspace,
        dry_run,
        name,
        debug,
        True,
        api_key=api_key,
    )


@import_group.command(name="prompt")
@click.argument("name", type=str)
@click.option(
    "--path",
    "-p",
    type=click.Path(file_okay=False, dir_okay=True, readable=True),
    default="opik_exports",
    help="Directory containing exported data. Defaults to opik_exports.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@click.pass_context
def import_prompt(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    debug: bool,
) -> None:
    """Import prompts from workspace/prompts directory.

    This command imports prompts matching the specified name from the path/prompts/ directory.
    The name is matched using case-insensitive substring matching.

    \b
    Examples:
    \b
        # Preview a prompt that would be imported
        opik import my-workspace prompt "my-prompt" --dry-run
    \b
        # Import a specific prompt
        opik import my-workspace prompt "my-prompt"
    \b
        # Import from a custom path
        opik import my-workspace prompt "my-prompt" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type("prompt", path, workspace, dry_run, name, debug, api_key=api_key)
