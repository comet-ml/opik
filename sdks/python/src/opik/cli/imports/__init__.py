"""Import command for Opik CLI."""

import sys
from typing import Dict, Optional

import click
from rich.console import Console

import opik

from .all import import_all_command
from .dataset import import_datasets_from_directory
from .experiment import import_experiments_from_directory
from .project import import_traces_from_directory
from .prompt import import_prompts_from_directory
from .utils import (
    debug_print,
    finalize_import,
    no_attachments_option,
    print_import_summary,
    resolve_import_project_root,
    setup_import_manifest,
    to_project_option,
    to_workspace_option,
)

console = Console()

IMPORT_CONTEXT_SETTINGS = {"help_option_names": ["-h", "--help"]}


def _import_by_type(
    import_type: str,
    path: str,
    workspace: str,
    project_name: str,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments: bool = False,
    api_key: Optional[str] = None,
    force: bool = False,
    include_attachments: bool = True,
    to_project: Optional[str] = None,
    to_workspace: Optional[str] = None,
) -> None:
    """
    Import data by type (dataset, traces, experiment, prompt) into a project.

    Args:
        import_type: Type of data to import ("dataset", "traces", "experiment", "prompt")
        path: Base directory containing the exported data
        workspace: Source workspace name. Used to locate the exported project folder
            under ``<path>/<workspace>/projects/``.
        project_name: Source project name. Used to locate the exported project
            folder (matched against the name recorded in project.json). Also the
            default destination project when ``to_project`` is not given.
        dry_run: Whether to show what would be imported without importing
        name_pattern: Optional string pattern to filter items by name
        debug: Enable debug output
        recreate_experiments: Whether to recreate experiments after importing
        force: Discard any existing manifest and restart from scratch
        to_project: Optional destination project name. When provided the data is
            created in this project instead of ``project_name``.
        to_workspace: Optional destination workspace name. When provided the data is
            imported into this workspace instead of ``workspace``. ``workspace`` is
            still used to locate the exported files on disk.
    """
    try:
        debug_print(f"DEBUG: Starting {import_type} import from {path}", debug)

        # Initialize Opik client using the destination workspace
        dest_workspace = to_workspace or workspace
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=dest_workspace)
        else:
            client = opik.Opik(workspace=dest_workspace)

        # Locate the exported project folder by its recorded name and resolve
        # the destination project. Folders are keyed by id on disk; project.json
        # holds the human name. The source folder is found under
        # PATH/WORKSPACE/projects/ (symmetric with export), falling back to
        # PATH/projects/ for cross-workspace imports. Data is created in
        # --to-project when given, else the source name.
        project_root, target_project_name = resolve_import_project_root(
            path, workspace, project_name, to_project
        )

        # Construct + initialize the per-destination manifest (keyed by the
        # destination workspace + project so different --to-workspace / --to-project
        # targets keep independent resume state). Skipped for --dry-run.
        manifest, already_completed = setup_import_manifest(
            project_root,
            target_project_name,
            dry_run,
            force,
            destination_workspace=dest_workspace,
        )
        if already_completed:
            return

        # ------------------------------------------------------------------
        # Determine source directory (project-nested layout)
        # ------------------------------------------------------------------
        if import_type == "dataset":
            source_dir = project_root / "datasets"
        elif import_type == "traces":
            source_dir = project_root
        elif import_type == "experiment":
            source_dir = project_root / "experiments"
        elif import_type == "prompt":
            source_dir = project_root / "prompts"
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
                client,
                source_dir,
                target_project_name,
                dry_run,
                name_pattern,
                debug,
                manifest=manifest,
            )
        elif import_type == "traces":
            stats = import_traces_from_directory(
                client,
                source_dir,
                target_project_name,
                dry_run,
                name_pattern,
                debug,
                recreate_experiments,
                manifest=manifest,
                include_attachments=include_attachments,
            )
        elif import_type == "experiment":
            stats = import_experiments_from_directory(
                client,
                source_dir,
                target_project_name,
                dry_run,
                name_pattern,
                debug,
                manifest=manifest,
            )
        elif import_type == "prompt":
            stats = import_prompts_from_directory(
                client,
                source_dir,
                target_project_name,
                dry_run,
                name_pattern,
                debug,
                manifest=manifest,
            )

        # Sum every "*_errors" counter, not just this item's: importers that
        # cascade (e.g. experiments pull in datasets/prompts/traces, and the
        # traces importer recreates experiments) and the whole-directory failure
        # path report under other keys, and those failures must not be masked.
        # Computed before manifest.complete() so a partial failure never marks
        # the manifest completed.
        total_errors = sum(
            value for key, value in stats.items() if key.endswith("_errors")
        )

        # Flush ingestion, surface upload failures, and complete the manifest
        # (only on a clean run). Shared with import_all.
        finalize_import(manifest, client, total_errors, dry_run)

        # Display summary
        print_import_summary(stats)

        # Map import_type to the stats dictionary key and a human-readable label.
        type_key_map = {
            "dataset": "datasets",
            "prompt": "prompts",
            "traces": "traces",
            "experiment": "experiments",
        }
        label_map = {
            "dataset": "datasets",
            "prompt": "prompts",
            "traces": "traces",
            "experiment": "experiments",
        }
        stats_key = type_key_map.get(import_type, import_type + "s")
        label = label_map.get(import_type, import_type + "s")
        imported_count = stats.get(stats_key, 0)

        if dry_run:
            console.print(
                f"[blue]Dry run complete: Would import {imported_count} {label}[/blue]"
            )
        elif total_errors > 0:
            # Individual errors were already printed in red by the importer;
            # exit non-zero so the failure is not masked by a 0 exit code.
            console.print(
                f"[red]Import completed with {total_errors} error(s) while importing {label} "
                "(see messages above). Re-run the import to retry.[/red]"
            )
            sys.exit(1)
        elif imported_count == 0:
            console.print(f"[yellow]No {label} were imported[/yellow]")
        else:
            console.print(
                f"[green]Successfully imported {imported_count} {label}[/green]"
            )

    except Exception as e:
        console.print(f"[red]Error importing {import_type}: {e}[/red]")
        sys.exit(1)


@click.group(name="import", context_settings=IMPORT_CONTEXT_SETTINGS)
@click.argument("workspace", type=str)
@click.argument("project", type=str)
@click.option(
    "--api-key",
    type=str,
    help="Opik API key. If not provided, will use OPIK_API_KEY environment variable or configuration.",
)
@click.pass_context
def import_group(
    ctx: click.Context,
    workspace: str,
    project: str,
    api_key: Optional[str],
) -> None:
    """Import data into an Opik project.

    In Opik v2 every dataset, prompt, and experiment belongs to a project, so
    imports are always scoped to a single project named on the command line.
    Data is read from the same layout ``opik export`` writes — folders are keyed
    by project id on disk and the project name is matched against each project's
    project.json, so the same ``--path`` round-trips between export and import.

    A migration_manifest.db file is automatically maintained under the project
    directory. If an import is interrupted, re-running the same command resumes
    from where it left off without creating duplicates. Use --force to discard
    the manifest and restart.

    \b
    General Usage:
        opik import WORKSPACE PROJECT ITEM [NAME] [OPTIONS]

    \b
    Data Types (ITEM):
        all         Import everything: datasets, prompts, traces, and experiments
        traces      Import the project's traces (and their spans)
        dataset     Import datasets from projects/PROJECT/datasets/
        experiment  Import experiments from projects/PROJECT/experiments/
        prompt      Import prompts from projects/PROJECT/prompts/

    \b
    Common Options:
        --path, -p       Directory containing exported data (default: opik_exports)
        --to-workspace   Destination workspace (default: same as source WORKSPACE)
        --dry-run        Preview what would be imported without actually importing
        --force          Discard manifest and restart from scratch
        --debug          Show detailed information about the import process

    \b
    Examples:
        # Import everything into the project
        opik import my-workspace my-project all

        # Preview what would be imported
        opik import my-workspace my-project all --dry-run

        # Import into a different workspace
        opik import source-workspace my-project all --to-workspace dest-workspace

        # Import into a different workspace and a different project
        opik import source-workspace my-project all --to-workspace dest-workspace --to-project new-project

        # Import the project's traces
        opik import my-workspace my-project traces

        # Import a specific dataset
        opik import my-workspace my-project dataset "my-dataset"

        # Import from a custom path
        opik import my-workspace my-project all --path ./custom-exports/

        # Resume an interrupted import (automatic — just re-run the same command)
        opik import my-workspace my-project all

        # Discard progress and restart from scratch
        opik import my-workspace my-project all --force
    """
    ctx.ensure_object(dict)
    ctx.obj["workspace"] = workspace
    ctx.obj["project_name"] = project
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

# Add the "all" subcommand (defined in all.py, registered here to avoid circular imports)
import_group.add_command(import_all_command)


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
    help="Show what would be imported without actually importing.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Discard the migration manifest and re-import everything from scratch.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@to_project_option()
@to_workspace_option()
@click.pass_context
def import_dataset(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    force: bool,
    debug: bool,
    to_project: Optional[str],
    to_workspace: Optional[str],
) -> None:
    """Import datasets from projects/PROJECT/datasets.

    This command imports datasets matching the specified name from the source
    project's datasets directory. The name is matched using case-insensitive
    substring matching.

    \b
    Examples:
    \b
        # Preview a dataset that would be imported
        opik import my-workspace my-project dataset "my-dataset" --dry-run
    \b
        # Import a specific dataset
        opik import my-workspace my-project dataset "my-dataset"
    \b
        # Import into a different destination project
        opik import my-workspace my-project dataset "my-dataset" --to-project other-project
    \b
        # Import into a different workspace
        opik import src-workspace my-project dataset "my-dataset" --to-workspace dest-workspace
    \b
        # Import from a custom path
        opik import my-workspace my-project dataset "my-dataset" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "dataset",
        path,
        workspace,
        project_name,
        dry_run,
        name,
        debug,
        api_key=api_key,
        force=force,
        to_project=to_project,
        to_workspace=to_workspace,
    )


@import_group.command(name="traces")
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
    "--force",
    is_flag=True,
    help="Discard the migration manifest and re-import everything from scratch.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@no_attachments_option()
@to_project_option()
@to_workspace_option()
@click.pass_context
def import_traces(
    ctx: click.Context,
    path: str,
    dry_run: bool,
    force: bool,
    debug: bool,
    no_attachments: bool,
    to_project: Optional[str],
    to_workspace: Optional[str],
) -> None:
    """Import the project's traces from projects/PROJECT/.

    Reads the trace files exported under the source project's folder and
    recreates the traces and their spans in the destination project.

    If a previous import was interrupted, re-running the same command automatically
    resumes from where it left off. Use --force to start over from scratch.

    \b
    Examples:
    \b
        # Preview the traces that would be imported
        opik import my-workspace my-project traces --dry-run
    \b
        # Import the project's traces
        opik import my-workspace my-project traces
    \b
        # Import into a different destination project
        opik import my-workspace my-project traces --to-project other-project
    \b
        # Import into a different workspace
        opik import src-workspace my-project traces --to-workspace dest-workspace
    \b
        # Import from a custom path
        opik import my-workspace my-project traces --path ./custom-exports/
    \b
        # Discard progress and restart
        opik import my-workspace my-project traces --force
    """
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "traces",
        path,
        workspace,
        project_name,
        dry_run,
        None,
        debug,
        True,  # Always recreate experiments when importing traces
        api_key=api_key,
        force=force,
        include_attachments=not no_attachments,
        to_project=to_project,
        to_workspace=to_workspace,
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
    "--force",
    is_flag=True,
    help="Discard the migration manifest and re-import everything from scratch.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@to_project_option()
@to_workspace_option()
@click.pass_context
def import_experiment(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    force: bool,
    debug: bool,
    to_project: Optional[str],
    to_workspace: Optional[str],
) -> None:
    """Import experiments from projects/PROJECT/experiments.

    This command imports experiments matching the specified name from the source
    project's experiments directory. The name is matched using case-insensitive
    substring matching.

    If a previous import was interrupted, re-running the same command automatically
    resumes from where it left off. Use --force to start over from scratch.

    \b
    Examples:
    \b
        # Preview an experiment that would be imported
        opik import my-workspace my-project experiment "my-experiment" --dry-run
    \b
        # Import a specific experiment
        opik import my-workspace my-project experiment "my-experiment"
    \b
        # Import into a different destination project
        opik import my-workspace my-project experiment "my-experiment" --to-project other-project
    \b
        # Import into a different workspace
        opik import src-workspace my-project experiment "my-experiment" --to-workspace dest-workspace
    \b
        # Import from a custom path
        opik import my-workspace my-project experiment "my-experiment" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "experiment",
        path,
        workspace,
        project_name,
        dry_run,
        name,
        debug,
        True,
        api_key=api_key,
        force=force,
        to_project=to_project,
        to_workspace=to_workspace,
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
    "--force",
    is_flag=True,
    help="Discard the migration manifest and re-import everything from scratch.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output to show detailed information about the import process.",
)
@to_project_option()
@to_workspace_option()
@click.pass_context
def import_prompt(
    ctx: click.Context,
    name: str,
    path: str,
    dry_run: bool,
    force: bool,
    debug: bool,
    to_project: Optional[str],
    to_workspace: Optional[str],
) -> None:
    """Import prompts from projects/PROJECT/prompts.

    This command imports prompts matching the specified name from the source
    project's prompts directory. The name is matched using case-insensitive
    substring matching.

    \b
    Examples:
    \b
        # Preview a prompt that would be imported
        opik import my-workspace my-project prompt "my-prompt" --dry-run
    \b
        # Import a specific prompt
        opik import my-workspace my-project prompt "my-prompt"
    \b
        # Import into a different destination project
        opik import my-workspace my-project prompt "my-prompt" --to-project other-project
    \b
        # Import into a different workspace
        opik import src-workspace my-project prompt "my-prompt" --to-workspace dest-workspace
    \b
        # Import from a custom path
        opik import my-workspace my-project prompt "my-prompt" --path ./custom-exports/
    """
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    _import_by_type(
        "prompt",
        path,
        workspace,
        project_name,
        dry_run,
        name,
        debug,
        api_key=api_key,
        force=force,
        to_project=to_project,
        to_workspace=to_workspace,
    )
