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
from .project import import_traces_from_directory
from .prompt import import_prompts_from_directory
from .utils import (
    available_project_names,
    debug_print,
    find_project_export_dir,
    no_attachments_option,
    print_import_summary,
    to_project_option,
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
) -> None:
    """Import all data types from the project export directory."""
    try:
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Locate the exported project folder by its recorded name (folders are
        # keyed by id on disk; project.json holds the human name). The workspace
        # segment mirrors the export layout (PATH/WORKSPACE/projects/<id>/) so
        # the same --path round-trips between export and import.
        base_path = Path(path) / workspace
        project_root = find_project_export_dir(base_path, project_name)
        if project_root is None:
            available = available_project_names(base_path)
            hint = (
                f" Available: {', '.join(available)}"
                if available
                else " No exported projects were found."
            )
            console.print(
                f"[red]No exported project named '{project_name}' under "
                f"{base_path / 'projects'}.{hint}[/red]"
            )
            sys.exit(1)

        # Data is created in --to-project when given, else the source name.
        project_name = to_project or project_name

        # ------------------------------------------------------------------
        # Manifest lifecycle (skipped for --dry-run)
        # ------------------------------------------------------------------
        manifest: Optional[MigrationManifest] = None

        if not dry_run:
            manifest = MigrationManifest(project_root)
            if force:
                if MigrationManifest.exists(project_root):
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
@no_attachments_option()
@to_project_option()
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
    )
