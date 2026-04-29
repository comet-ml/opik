"""``opik copy dataset`` — copy a dataset (and its experiments + traces +
spans by default) from a source location into a destination project on the
same Opik instance.

Implementation: thin orchestration over the existing ``opik export`` and
``opik import`` flows. The export pass writes to a persistent run directory
under ``~/.opik/copy-runs/``; the import pass reads from that directory and
overrides every ``project_name`` it would otherwise infer with
``--destination-project``.
"""

import shutil
import sys
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional

import click
from rich.console import Console
from rich.table import Table

import opik
from opik import exceptions

from ..exports.dataset import export_dataset_by_name
from ..exports.experiment import export_experiment_by_name_or_id
from ..imports.dataset import import_datasets_from_directory
from ..imports.experiment import import_experiments_from_directory
from ..migration_manifest import MigrationManifest

console = Console()


COPY_RUNS_DIR = Path.home() / ".opik" / "copy-runs"


def _make_run_dir(workspace: str, dataset_name: str) -> Path:
    """Return a persistent, per-run directory under ~/.opik/copy-runs/.

    Persistent (not /tmp) so an interrupted copy can be resumed by re-running
    the same command — the ``MigrationManifest`` lives inside.
    """
    safe_workspace = "".join(c if c.isalnum() or c in "-_" else "_" for c in workspace)
    safe_dataset = "".join(c if c.isalnum() or c in "-_" else "_" for c in dataset_name)
    timestamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    run_dir = COPY_RUNS_DIR / f"{safe_workspace}-{safe_dataset}-{timestamp}"
    run_dir.mkdir(parents=True, exist_ok=True)
    return run_dir


def _scan_run_dir(run_dir: Path, workspace: str) -> Dict[str, int]:
    """Count exported entities on disk so the pre-flight summary can show them."""
    workspace_root = run_dir / workspace
    counts = {
        "datasets": 0,
        "dataset_items": 0,
        "experiments": 0,
        "traces": 0,
        "spans": 0,
    }

    datasets_dir = workspace_root / "datasets"
    if datasets_dir.exists():
        import json

        for f in datasets_dir.glob("dataset_*.json"):
            counts["datasets"] += 1
            try:
                with open(f) as fh:
                    counts["dataset_items"] += len(json.load(fh).get("items", []))
            except (OSError, json.JSONDecodeError):
                pass

    experiments_dir = workspace_root / "experiments"
    if experiments_dir.exists():
        counts["experiments"] = len(list(experiments_dir.glob("experiment_*.json")))

    projects_dir = workspace_root / "projects"
    if projects_dir.exists():
        import json

        for project_dir in projects_dir.iterdir():
            if not project_dir.is_dir():
                continue
            for trace_file in project_dir.glob("trace_*.json"):
                counts["traces"] += 1
                try:
                    with open(trace_file) as fh:
                        counts["spans"] += len(json.load(fh).get("spans", []))
                except (OSError, json.JSONDecodeError):
                    pass

    return counts


def _print_preflight_summary(
    counts: Dict[str, int],
    dataset_name: str,
    destination_project: str,
    exclude_experiments: bool,
) -> None:
    """Render a Rich table with what's about to be copied."""
    table = Table(
        title=f"About to copy '{dataset_name}' → '{destination_project}'",
        show_header=False,
        box=None,
        pad_edge=False,
    )
    table.add_column("Asset", style="bold")
    table.add_column("Count", justify="right", style="cyan")

    table.add_row("Dataset", "1")
    table.add_row("Dataset items", str(counts["dataset_items"]))
    if not exclude_experiments:
        table.add_row("Experiments", str(counts["experiments"]))
        table.add_row("Traces", str(counts["traces"]))
        table.add_row("Spans", str(counts["spans"]))
    console.print(table)


def _filter_experiments_by_source_project(
    workspace_root: Path, source_project: str
) -> int:
    """Drop exported experiment + trace artefacts whose project doesn't match.

    The export pass pulls every experiment that references the source dataset.
    When ``--source-project`` is given, prune anything outside that project so
    the import pass only recreates the matching subset.

    Returns the number of experiment files retained.
    """
    import json

    experiments_dir = workspace_root / "experiments"
    projects_dir = workspace_root / "projects"
    retained = 0

    if experiments_dir.exists():
        for exp_file in list(experiments_dir.glob("experiment_*.json")):
            try:
                with open(exp_file) as fh:
                    data = json.load(fh)
                exp_project = (data.get("experiment", {}).get("metadata") or {}).get(
                    "project_name"
                )
                # Fall back to inferring from the trace project dirs if metadata is missing.
                if exp_project is None and projects_dir.exists():
                    items = data.get("items") or []
                    if items:
                        first_trace_id = items[0].get("trace_id")
                        if first_trace_id:
                            for project_dir in projects_dir.iterdir():
                                if (
                                    project_dir / f"trace_{first_trace_id}.json"
                                ).exists():
                                    exp_project = project_dir.name
                                    break
                if exp_project != source_project:
                    exp_file.unlink()
                else:
                    retained += 1
            except (OSError, json.JSONDecodeError):
                continue

    # Drop trace project directories that don't belong to the source project.
    if projects_dir.exists():
        for project_dir in list(projects_dir.iterdir()):
            if project_dir.is_dir() and project_dir.name != source_project:
                shutil.rmtree(project_dir, ignore_errors=True)

    return retained


def _verify_destination_counts(
    client: opik.Opik,
    dataset_name: str,
    destination_project: str,
    expected: Dict[str, int],
    exclude_experiments: bool,
) -> bool:
    """Best-effort post-copy count diff against the destination.

    Returns True when the actual destination counts match what was exported.
    Logs a warning and returns False on any mismatch — the caller decides
    whether to exit non-zero.
    """
    try:
        dest_dataset = client.get_dataset(
            dataset_name, project_name=destination_project
        )
        actual_items = len(dest_dataset.get_items())
    except Exception as e:
        console.print(
            f"[yellow]Could not verify destination dataset items: {e}[/yellow]"
        )
        return False

    table = Table(
        title="Post-copy verification",
        show_header=True,
        box=None,
        pad_edge=False,
    )
    table.add_column("Asset")
    table.add_column("Source", justify="right")
    table.add_column("Destination", justify="right")
    table.add_column("Match", justify="center")

    matched = True
    items_match = actual_items == expected["dataset_items"]
    matched = matched and items_match
    table.add_row(
        "Dataset items",
        str(expected["dataset_items"]),
        str(actual_items),
        "✓" if items_match else "✗",
    )

    if not exclude_experiments:
        try:
            actual_experiments = len(
                client.get_dataset_experiments(
                    dataset_name=dataset_name,
                    project_name=destination_project,
                    max_results=10_000,
                )
            )
        except Exception as e:
            console.print(
                f"[yellow]Could not verify destination experiments: {e}[/yellow]"
            )
            actual_experiments = -1

        exp_match = actual_experiments == expected["experiments"]
        matched = matched and exp_match
        table.add_row(
            "Experiments",
            str(expected["experiments"]),
            str(actual_experiments) if actual_experiments >= 0 else "?",
            "✓" if exp_match else "?" if actual_experiments < 0 else "✗",
        )

    console.print(table)
    return matched


@click.command(name="dataset")
@click.argument("name", type=str)
@click.option(
    "--destination-project",
    required=True,
    type=str,
    help="Project name to copy into (required). Auto-created on the destination if missing.",
)
@click.option(
    "--source-project",
    type=str,
    default=None,
    help="Optional. Scopes which experiments tag along. Omit to pull every experiment that references the dataset across the workspace.",
)
@click.option(
    "--exclude-experiments",
    is_flag=True,
    help="Copy only the dataset definition + items; skip experiments, traces, and spans.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Run the export pass and print the pre-flight summary without writing to the destination.",
)
@click.option(
    "--debug",
    is_flag=True,
    help="Enable debug output and keep the run directory after success for inspection.",
)
@click.option(
    "--force",
    is_flag=True,
    help="Discard any existing migration manifest in the run directory and start fresh.",
)
@click.option(
    "--yes",
    "-y",
    is_flag=True,
    help="Skip the pre-flight confirmation prompt (useful for scripts and CI).",
)
@click.pass_context
def copy_dataset_command(
    ctx: click.Context,
    name: str,
    destination_project: str,
    source_project: Optional[str],
    exclude_experiments: bool,
    dry_run: bool,
    debug: bool,
    force: bool,
    yes: bool,
) -> None:
    """Copy a dataset (and its experiments + traces + spans) into a destination project.

    \b
    Examples:
    \b
        # Copy a workspace-level dataset into Project B (full transitive copy).
        opik copy my-workspace dataset "MyDataset" --destination-project "Project B"
    \b
        # Dataset definition + items only.
        opik copy my-workspace dataset "MyDataset" \\
            --destination-project "Project B" --exclude-experiments
    \b
        # Scope to experiments from a specific source project.
        opik copy my-workspace dataset "MyDataset" \\
            --source-project "Project A" --destination-project "Project B"
    \b
        # Preview what would be copied without writing anything to the destination.
        opik copy my-workspace dataset "MyDataset" \\
            --destination-project "Project B" --dry-run
    """
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None

    client = (
        opik.Opik(api_key=api_key, workspace=workspace)
        if api_key
        else opik.Opik(workspace=workspace)
    )

    # ------------------------------------------------------------------
    # Pre-flight: source dataset must exist; destination project doesn't have
    # to exist yet (the backend creates it on first write). We don't add a new
    # permissions endpoint here — an unauthorised destination will fail at
    # the first import write, which is good enough for v1.
    # ------------------------------------------------------------------
    try:
        client.get_dataset(name, project_name=source_project)
    except exceptions.DatasetNotFound:
        console.print(
            f"[red]Source dataset '{name}' not found"
            + (f" in project '{source_project}'." if source_project else ".")
            + "[/red]"
        )
        sys.exit(1)
    except Exception as e:
        console.print(f"[red]Could not look up source dataset '{name}': {e}[/red]")
        sys.exit(1)

    run_dir = _make_run_dir(workspace, name)
    workspace_root = run_dir / workspace

    if debug:
        console.print(f"[blue]Run directory: {run_dir}[/blue]")

    # ------------------------------------------------------------------
    # Export phase — reuse the existing export functions to dump everything
    # into the run dir. We always go via files (not in-memory) so resumability
    # via MigrationManifest works the same way as ``opik import``.
    # ------------------------------------------------------------------
    console.print(f"[blue]Exporting '{name}' from workspace '{workspace}'...[/blue]")

    if exclude_experiments:
        export_dataset_by_name(
            name=name,
            workspace=workspace,
            output_path=str(run_dir),
            max_results=None,
            force=False,
            debug=debug,
            format="json",
            api_key=api_key,
        )
    else:
        # Pull every experiment that references this dataset (filtered by
        # source project later, on disk, if --source-project was given).
        experiments_to_export = client.get_dataset_experiments(
            dataset_name=name,
            project_name=source_project,
            max_results=10_000,
        )
        if not experiments_to_export:
            # No experiments tag along → fall back to dataset-only export.
            export_dataset_by_name(
                name=name,
                workspace=workspace,
                output_path=str(run_dir),
                max_results=None,
                force=False,
                debug=debug,
                format="json",
                api_key=api_key,
            )
        else:
            # The experiment exporter pulls the dataset along with each
            # experiment, so a single call covers dataset + items + traces +
            # spans + experiments.
            for exp in experiments_to_export:
                export_experiment_by_name_or_id(
                    name_or_id=exp.id,
                    workspace=workspace,
                    output_path=str(run_dir),
                    dataset=name,
                    max_traces=None,
                    force=False,
                    debug=debug,
                    format="json",
                    api_key=api_key,
                )

            if source_project is not None:
                retained = _filter_experiments_by_source_project(
                    workspace_root, source_project
                )
                if debug:
                    console.print(
                        f"[blue]--source-project filter: retained {retained} experiment(s) from '{source_project}'[/blue]"
                    )

    # ------------------------------------------------------------------
    # Pre-flight summary + confirmation.
    # ------------------------------------------------------------------
    counts = _scan_run_dir(run_dir, workspace)
    _print_preflight_summary(counts, name, destination_project, exclude_experiments)

    if dry_run:
        console.print("[blue]Dry run — destination not modified.[/blue]")
        if debug:
            console.print(f"[blue]Run dir kept at: {run_dir}[/blue]")
        else:
            shutil.rmtree(run_dir, ignore_errors=True)
        return

    if not yes:
        if not click.confirm("Proceed with copy?", default=True):
            console.print("[yellow]Aborted by user.[/yellow]")
            shutil.rmtree(run_dir, ignore_errors=True)
            sys.exit(0)

    # ------------------------------------------------------------------
    # Import phase — reuse existing functions with destination_project override.
    # MigrationManifest in the run dir handles resumability; --force resets it.
    # ------------------------------------------------------------------
    manifest = MigrationManifest(workspace_root)
    if force and MigrationManifest.exists(workspace_root):
        manifest.reset()
        console.print(
            "[yellow]--force: discarded existing manifest, starting fresh[/yellow]"
        )
    elif manifest.is_completed:
        console.print(
            "[green]This copy run was already completed. Use --force to redo it.[/green]"
        )
        return
    elif manifest.is_in_progress:
        console.print(
            f"[blue]Resuming interrupted copy: "
            f"{manifest.completed_count()} file(s) already imported[/blue]"
        )
    manifest.start()

    datasets_dir = workspace_root / "datasets"
    if datasets_dir.exists():
        import_datasets_from_directory(
            client=client,
            source_dir=datasets_dir,
            dry_run=False,
            name_pattern=name,
            debug=debug,
            manifest=manifest,
            destination_project=destination_project,
        )

    if not exclude_experiments:
        experiments_dir = workspace_root / "experiments"
        if experiments_dir.exists() and any(experiments_dir.glob("experiment_*.json")):
            import_experiments_from_directory(
                client=client,
                source_dir=experiments_dir,
                dry_run=False,
                name_pattern=None,
                debug=debug,
                manifest=manifest,
                destination_project=destination_project,
            )

    flushed = client.flush()
    if not flushed:
        console.print(
            "[yellow]Warning: flush timed out — some traces/spans may not have been ingested. "
            "Re-run the same command to resume.[/yellow]"
        )
        sys.exit(1)

    manifest.complete()

    # ------------------------------------------------------------------
    # Post-copy verification — count diff, source vs destination.
    # ------------------------------------------------------------------
    matched = _verify_destination_counts(
        client, name, destination_project, counts, exclude_experiments
    )

    # Cleanup unless --debug.
    if debug:
        console.print(f"[blue]Run dir kept at: {run_dir}[/blue]")
    else:
        shutil.rmtree(run_dir, ignore_errors=True)

    if matched:
        console.print(
            f"[green]Successfully copied '{name}' into '{destination_project}'.[/green]"
        )
    else:
        console.print(
            f"[yellow]Copied '{name}' into '{destination_project}', but post-copy counts didn't match. "
            f"Re-run the command to retry.[/yellow]"
        )
        sys.exit(1)
