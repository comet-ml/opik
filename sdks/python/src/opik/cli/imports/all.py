"""Import all workspace data."""

import sys
from typing import Dict, List, Optional

import click
from rich.console import Console

import opik

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
from ..include_validation import validate_include

console = Console()

_VALID_INCLUDES = {"datasets", "prompts", "traces", "experiments"}
_DEFAULT_INCLUDE = "datasets,prompts,traces,experiments"


def _validate_include(
    ctx: click.Context, param: click.Parameter, value: str
) -> List[str]:
    return validate_include(value, _VALID_INCLUDES, ctx, param)


def _merge_stats(total: Dict[str, int], phase: Dict[str, int]) -> None:
    """Accumulate phase stats into the running total."""
    for key, value in phase.items():
        total[key] = total.get(key, 0) + value


def import_all(
    workspace: str,
    project_name: str,
    path: str,
    include: List[str],
    dry_run: bool,
    force: bool,
    debug: bool,
    api_key: Optional[str] = None,
    include_attachments: bool = True,
    to_project: Optional[str] = None,
    to_workspace: Optional[str] = None,
) -> None:
    """Import all data types from the project export directory."""
    try:
        dest_workspace = to_workspace or workspace
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=dest_workspace)
        else:
            client = opik.Opik(workspace=dest_workspace)

        # Locate the exported project folder by its recorded name and resolve
        # the destination project (shared with _import_by_type). Folders are
        # keyed by id on disk; project.json holds the human name. project_name
        # becomes the destination — --to-project when given, else the source.
        project_root, project_name = resolve_import_project_root(
            path, workspace, project_name, to_project
        )

        # Construct + initialize the per-destination manifest (shared with
        # _import_by_type; keyed by destination workspace + project). Skipped
        # for --dry-run.
        manifest, already_completed = setup_import_manifest(
            project_root,
            project_name,
            dry_run,
            force,
            destination_workspace=dest_workspace,
        )
        if already_completed:
            return

        total_stats: Dict[str, int] = {}

        # ------------------------------------------------------------------
        # Phase 1 — Datasets
        # ------------------------------------------------------------------
        if "datasets" in include:
            datasets_dir = project_root / "datasets"
            if datasets_dir.exists():
                console.print("\n[bold blue]--- Importing Datasets ---[/bold blue]")
                stats = import_datasets_from_directory(
                    client,
                    datasets_dir,
                    project_name,
                    dry_run,
                    None,
                    debug,
                    manifest=manifest,
                )
                _merge_stats(total_stats, stats)
            else:
                debug_print(f"No datasets directory at {datasets_dir}, skipping", debug)

        # ------------------------------------------------------------------
        # Phase 2 — Prompts
        # ------------------------------------------------------------------
        if "prompts" in include:
            prompts_dir = project_root / "prompts"
            if prompts_dir.exists():
                console.print("\n[bold blue]--- Importing Prompts ---[/bold blue]")
                stats = import_prompts_from_directory(
                    client,
                    prompts_dir,
                    project_name,
                    dry_run,
                    None,
                    debug,
                    manifest=manifest,
                )
                _merge_stats(total_stats, stats)
                # Flush so prompts are available before experiments reference them
                if not dry_run and stats.get("prompts", 0) > 0:
                    client.flush()
            else:
                debug_print(f"No prompts directory at {prompts_dir}, skipping", debug)

        # ------------------------------------------------------------------
        # Phase 3 — Traces
        # Populates trace ID mappings in the manifest for experiments to use.
        # ------------------------------------------------------------------
        if "traces" in include:
            console.print("\n[bold blue]--- Importing Traces ---[/bold blue]")
            stats = import_traces_from_directory(
                client,
                project_root,
                project_name,
                dry_run,
                None,
                debug,
                recreate_experiments_flag=False,
                manifest=manifest,
                include_attachments=include_attachments,
            )
            _merge_stats(total_stats, stats)

        # ------------------------------------------------------------------
        # Phase 4 — Experiments
        # import_experiments_from_directory internally re-calls the dataset,
        # prompt, and trace importers, but the shared manifest skips any files
        # already completed in phases 1–3. It reads the trace_id_map built in
        # phase 3 directly from the manifest DB.
        # ------------------------------------------------------------------
        if "experiments" in include:
            experiments_dir = project_root / "experiments"
            if experiments_dir.exists():
                console.print("\n[bold blue]--- Importing Experiments ---[/bold blue]")
                stats = import_experiments_from_directory(
                    client,
                    experiments_dir,
                    project_name,
                    dry_run,
                    None,
                    debug,
                    manifest=manifest,
                )
                _merge_stats(total_stats, stats)
            else:
                debug_print(
                    f"No experiments directory at {experiments_dir}, skipping", debug
                )

        # ------------------------------------------------------------------
        # Flush ingestion queue and check for upload failures
        # ------------------------------------------------------------------
        # Total error count across all phases — computed before manifest.complete()
        # so a partial failure never marks the manifest completed.
        total_errors = sum(
            value for key, value in total_stats.items() if key.endswith("_errors")
        )

        # Flush ingestion, surface upload failures, and complete the manifest
        # (only on a clean run). Shared with _import_by_type.
        finalize_import(manifest, client, total_errors, dry_run)

        # ------------------------------------------------------------------
        # Summary
        # ------------------------------------------------------------------
        if total_errors > 0 and not dry_run:
            print_import_summary(total_stats)
            # Per-item errors were already printed in red by the importers; exit
            # non-zero so the failure is not masked by a 0 exit code.
            console.print(
                f"\n[bold red]Import completed with {total_errors} error(s) "
                "(see messages above). Re-run the import to retry.[/bold red]"
            )
            sys.exit(1)

        console.print("\n[bold green]Import complete.[/bold green]")
        print_import_summary(total_stats)

        if dry_run:
            console.print("[blue]Dry run complete — no data was written.[/blue]")

    except Exception as e:
        console.print(f"[red]Error during import all: {e}[/red]")
        if debug:
            import traceback

            debug_print(traceback.format_exc(), debug)
        sys.exit(1)


@click.command(name="all")
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
@click.option(
    "--include",
    default=_DEFAULT_INCLUDE,
    callback=_validate_include,
    help=(
        "Comma-separated list of data types to import. "
        f"Valid values: {', '.join(sorted(_VALID_INCLUDES))}. "
        f"Defaults to all: {_DEFAULT_INCLUDE}."
    ),
)
@no_attachments_option()
@to_project_option()
@to_workspace_option()
@click.pass_context
def import_all_command(
    ctx: click.Context,
    path: str,
    dry_run: bool,
    force: bool,
    debug: bool,
    include: List[str],
    no_attachments: bool,
    to_project: Optional[str],
    to_workspace: Optional[str],
) -> None:
    """Import all datasets, prompts, traces, and experiments into the project.

    Reads from the directory structure produced by 'opik export WORKSPACE PROJECT all'.
    A single migration manifest tracks progress across all data types, so an
    interrupted import can be resumed by re-running the same command.

    Import order: datasets → prompts → traces → experiments.
    Experiments depend on datasets, prompts, and traces being present,
    so earlier phases are always run first when included.

    \b
    Examples:
        # Import everything
        opik import my-workspace my-project all

        # Preview what would be imported
        opik import my-workspace my-project all --dry-run

        # Import only datasets and prompts
        opik import my-workspace my-project all --include datasets,prompts

        # Import into a different workspace
        opik import src-workspace my-project all --to-workspace dest-workspace

        # Import into a different workspace and project
        opik import src-workspace my-project all --to-workspace dest-workspace --to-project new-project

        # Restart from scratch, discarding previous progress
        opik import my-workspace my-project all --force

        # Import from a custom path
        opik import my-workspace my-project all --path ./backup
    """
    workspace = ctx.obj["workspace"]
    project_name = ctx.obj["project_name"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    import_all(
        workspace,
        project_name,
        path,
        include,
        dry_run,
        force,
        debug,
        api_key,
        include_attachments=not no_attachments,
        to_project=to_project,
        to_workspace=to_workspace,
    )
