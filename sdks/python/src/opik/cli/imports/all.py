"""Import all workspace data."""

import sys
from pathlib import Path
from typing import Dict, List, Optional

import click
from rich.console import Console

import opik

from ..migration_manifest import MigrationManifest
from .dataset import import_datasets_from_directory
from .experiment import import_experiments_from_directory
from .project import import_projects_from_directory
from .prompt import import_prompts_from_directory
from .utils import debug_print, print_import_summary
from ..include_validation import validate_include

console = Console()

_VALID_INCLUDES = {"datasets", "prompts", "projects", "experiments"}
_DEFAULT_INCLUDE = "datasets,prompts,projects,experiments"


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
    path: str,
    include: List[str],
    dry_run: bool,
    force: bool,
    debug: bool,
    api_key: Optional[str] = None,
) -> None:
    """Import all data types from the workspace export directory."""
    try:
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace, _use_batching=True)
        else:
            client = opik.Opik(workspace=workspace, _use_batching=True)

        base_path = Path(path)

        # ------------------------------------------------------------------
        # Manifest lifecycle (skipped for --dry-run)
        # ------------------------------------------------------------------
        manifest: Optional[MigrationManifest] = None

        if not dry_run:
            manifest = MigrationManifest(base_path)
            if force:
                if MigrationManifest.exists(base_path):
                    manifest.reset()
                    console.print(
                        "[yellow]--force: discarding existing manifest, starting fresh[/yellow]"
                    )
            else:
                if manifest.is_completed:
                    console.print(
                        "[green]Import already completed. Use --force to re-import.[/green]"
                    )
                    return
                elif manifest.is_in_progress:
                    console.print(
                        f"[blue]Resuming interrupted import: "
                        f"{manifest.completed_count()} file(s) already completed[/blue]"
                    )
            manifest.start()

        total_stats: Dict[str, int] = {}

        # ------------------------------------------------------------------
        # Phase 1 — Datasets
        # ------------------------------------------------------------------
        if "datasets" in include:
            datasets_dir = base_path / "datasets"
            if datasets_dir.exists():
                console.print("\n[bold blue]--- Importing Datasets ---[/bold blue]")
                stats = import_datasets_from_directory(
                    client, datasets_dir, dry_run, None, debug, manifest=manifest
                )
                _merge_stats(total_stats, stats)
            else:
                debug_print(f"No datasets directory at {datasets_dir}, skipping", debug)

        # ------------------------------------------------------------------
        # Phase 2 — Prompts
        # ------------------------------------------------------------------
        if "prompts" in include:
            prompts_dir = base_path / "prompts"
            if prompts_dir.exists():
                console.print("\n[bold blue]--- Importing Prompts ---[/bold blue]")
                stats = import_prompts_from_directory(
                    client, prompts_dir, dry_run, None, debug, manifest=manifest
                )
                _merge_stats(total_stats, stats)
                # Flush so prompts are available before experiments reference them
                if not dry_run and stats.get("prompts", 0) > 0:
                    client.flush()
            else:
                debug_print(f"No prompts directory at {prompts_dir}, skipping", debug)

        # ------------------------------------------------------------------
        # Phase 3 — Projects (traces)
        # Populates trace ID mappings in the manifest for experiments to use.
        # ------------------------------------------------------------------
        if "projects" in include:
            projects_dir = base_path / "projects"
            if projects_dir.exists():
                console.print("\n[bold blue]--- Importing Projects ---[/bold blue]")
                stats = import_projects_from_directory(
                    client,
                    projects_dir,
                    dry_run,
                    None,
                    debug,
                    recreate_experiments_flag=False,
                    manifest=manifest,
                )
                _merge_stats(total_stats, stats)
            else:
                debug_print(f"No projects directory at {projects_dir}, skipping", debug)

        # ------------------------------------------------------------------
        # Phase 4 — Experiments
        # import_experiments_from_directory internally re-calls the dataset,
        # prompt, and trace importers, but the shared manifest skips any files
        # already completed in phases 1–3. It reads the trace_id_map built in
        # phase 3 directly from the manifest DB.
        # ------------------------------------------------------------------
        if "experiments" in include:
            experiments_dir = base_path / "experiments"
            if experiments_dir.exists():
                console.print("\n[bold blue]--- Importing Experiments ---[/bold blue]")
                stats = import_experiments_from_directory(
                    client, experiments_dir, dry_run, None, debug, manifest=manifest
                )
                _merge_stats(total_stats, stats)
            else:
                debug_print(
                    f"No experiments directory at {experiments_dir}, skipping", debug
                )

        # ------------------------------------------------------------------
        # Flush ingestion queue and check for upload failures
        # ------------------------------------------------------------------
        if not dry_run:
            flushed = client.flush()
            if not flushed:
                console.print(
                    "[yellow]Warning: flush timed out — some traces/spans may not have been "
                    "ingested. Re-run the import to retry.[/yellow]"
                )
                sys.exit(1)

            failed = client.__internal_api__failed_uploads__(timeout=None)
            if failed > 0:
                console.print(
                    f"[yellow]Warning: {failed} file upload(s) failed during import. "
                    "Re-run the import to retry.[/yellow]"
                )
                sys.exit(1)

            assert manifest is not None
            manifest.complete()

        # ------------------------------------------------------------------
        # Summary
        # ------------------------------------------------------------------
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
@click.pass_context
def import_all_command(
    ctx: click.Context,
    path: str,
    dry_run: bool,
    force: bool,
    debug: bool,
    include: List[str],
) -> None:
    """Import all datasets, prompts, projects, and experiments into the workspace.

    Reads from the directory structure produced by 'opik export WORKSPACE all'.
    A single migration manifest tracks progress across all data types, so an
    interrupted import can be resumed by re-running the same command.

    Import order: datasets → prompts → projects → experiments.
    Experiments depend on datasets, prompts, and project traces being present,
    so earlier phases are always run first when included.

    \b
    Examples:
        # Import everything
        opik import my-workspace all

        # Preview what would be imported
        opik import my-workspace all --dry-run

        # Import only datasets and prompts
        opik import my-workspace all --include datasets,prompts

        # Restart from scratch, discarding previous progress
        opik import my-workspace all --force

        # Import from a custom path
        opik import my-workspace all --path ./backup
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    import_all(workspace, path, include, dry_run, force, debug, api_key)
