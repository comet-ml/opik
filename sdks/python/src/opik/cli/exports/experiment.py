"""Experiment export functionality."""

import json
import sys
import threading
from concurrent.futures import Future, ThreadPoolExecutor, as_completed
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional, List, Dict, Set, Tuple

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
from opik.cli.export_manifest import ExportManifest
from .utils import (
    create_experiment_data_structure,
    debug_print,
    extract_trace_id_from_filename,
    write_json_data,
    write_csv_data,
    print_export_summary,
    should_skip_file,
    trace_to_csv_rows,
)
from .trace_filter import matches_trace_filter
from .dataset import export_experiment_datasets
from .prompt import (
    export_related_prompts_by_name,
    export_prompts_by_ids,
)

console = Console()

# Batch size for parallel trace fetching
BATCH_SIZE = 100
# Maximum number of concurrent workers for parallel execution
MAX_WORKERS = 20


def _fetch_trace_data(
    client: opik.Opik,
    trace_id: str,
    project_name_cache: dict[str, str],
    cache_lock: threading.Lock,
    debug: bool,
) -> Optional[Tuple[str, dict, str]]:
    """Fetch trace and span data for a single trace ID.

    Returns:
        Tuple of (trace_id, trace_data_dict, project_name) or None if failed.
    """
    try:
        # Get trace by ID
        trace = client.get_trace_content(trace_id)

        # Get project name for this trace
        if not trace.project_id:
            return None

        # Get project name (use cache if available).
        # Fast path: check without the lock first (already-cached entries).
        # If missing, fetch without holding the lock (slow API call), then
        # use setdefault so that the first writer wins in case two threads
        # raced on the same project_id.
        if trace.project_id not in project_name_cache:
            try:
                project = client.get_project(trace.project_id)
                with cache_lock:
                    project_name_cache.setdefault(trace.project_id, project.name)
            except Exception as e:
                if debug:
                    debug_print(
                        f"Warning: Could not get project for trace {trace_id}: {e}",
                        debug,
                    )
                return None

        project_name = project_name_cache[trace.project_id]

        # Get spans for this trace
        spans = client.search_spans(
            trace_id=trace_id,
            max_results=1000,
            truncate=False,
        )

        # Create trace data structure
        trace_data = {
            "trace": trace.model_dump(),
            "spans": [span.model_dump() for span in spans],
            "downloaded_at": datetime.now().isoformat(),
            "project_name": project_name,
        }

        return (trace_id, trace_data, project_name)
    except Exception as e:
        if debug:
            import traceback

            debug_print(
                f"Error fetching trace {trace_id}: {e}\n{traceback.format_exc()}", debug
            )
        return None


def _write_trace_file(
    trace_id: str,
    trace_data: dict,
    project_name: str,
    workspace_root: Path,
    format: str,
    force: bool,
    debug: bool,
) -> bool:
    """Write a single trace to file. Returns True if exported, False if skipped."""
    try:
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
                debug_print(f"Skipping trace {trace_id} (already exists)", debug)
            return False

        # Save to file using the appropriate format
        if format.lower() == "csv":
            write_csv_data(trace_data, file_path, trace_to_csv_rows)
            if debug:
                debug_print(f"Wrote CSV file: {file_path}", debug)
        else:
            write_json_data(trace_data, file_path)
            if debug:
                debug_print(f"Wrote JSON file: {file_path}", debug)

        return True
    except Exception as e:
        console.print(f"[red]Error writing trace {trace_id} to file: {e}[/red]")
        if debug:
            import traceback

            debug_print(f"Traceback: {traceback.format_exc()}", debug)
        return False


def _scan_downloaded_trace_ids(workspace_root: Path, format: str) -> Set[str]:
    """Scan projects/ dirs for already-downloaded trace files and return their IDs.

    This is a fast, zero-network way to pre-filter traces before making any API
    calls.  A single glob over the output directory is all it takes.
    """
    ext = "csv" if format.lower() == "csv" else "json"
    downloaded: Set[str] = set()
    projects_dir = workspace_root / "projects"
    if not projects_dir.is_dir():
        return downloaded
    for trace_file in projects_dir.glob(f"*/trace_*.{ext}"):
        trace_id = extract_trace_id_from_filename(trace_file)
        if trace_id:
            downloaded.add(trace_id)
    return downloaded


def export_collected_trace_ids(
    client: opik.Opik,
    workspace_root: Path,
    all_trace_ids: set,
    max_limit: Optional[int],
    format: str,
    debug: bool,
    force: bool,
    manifest: Optional[ExportManifest] = None,
    filter_string: Optional[str] = None,
) -> tuple[int, int]:
    """Export a collected set of trace IDs, applying the optional limit.

    This is the common tail of both the ``export experiment`` and ``export all``
    flows: cap ``all_trace_ids`` to *max_limit*, print a status line, then
    delegate to :func:`export_traces_by_ids`.  Returns ``(exported, skipped)``.
    """
    if not all_trace_ids:
        return 0, 0
    trace_ids_list = list(all_trace_ids)
    if max_limit:
        trace_ids_list = trace_ids_list[:max_limit]
    if len(trace_ids_list) > 1:
        console.print(
            f"[blue]Exporting {len(trace_ids_list)} unique trace(s) from experiments...[/blue]"
        )
    return export_traces_by_ids(
        client,
        trace_ids_list,
        workspace_root,
        None,
        format,
        debug,
        force,
        manifest=manifest,
        filter_string=filter_string,
    )


def export_traces_by_ids(
    client: opik.Opik,
    trace_ids: List[str],
    workspace_root: Path,
    max_traces: Optional[int],
    format: str,
    debug: bool,
    force: bool,
    manifest: Optional[ExportManifest] = None,
    filter_string: Optional[str] = None,
) -> tuple[int, int]:
    """Export traces by their IDs using parallel batch processing.

    Traces are saved in projects/PROJECT_NAME/ directory based on each trace's project.
    Uses parallel execution to fetch traces/spans and write files concurrently.

    Already-downloaded traces are skipped before any API call is made:
    - When *manifest* is provided its downloaded-ID set is used for the check.
    - Otherwise the projects/ directory is scanned for existing trace files.
    """
    exported_count = 0
    skipped_count = 0
    failed_count = 0

    if max_traces:
        trace_ids = trace_ids[:max_traces]

    if not trace_ids:
        return 0, 0

    # Record start time before first API call so manifest.complete() can store it.
    export_start_time = datetime.now(timezone.utc).isoformat()

    # ------------------------------------------------------------------
    # Phase 1 / Phase 2 pre-filter: skip already-downloaded traces so we
    # never make a network call for a trace whose file already exists.
    # ------------------------------------------------------------------
    if not force:
        if manifest:
            already_downloaded = manifest.load_downloaded_set()
            # A freshly created manifest returns an empty set even when files already
            # exist on disk from an earlier run.  Fall back to a filesystem scan so
            # pre-existing traces are detected and skipped before any API call.
            if not already_downloaded:
                already_downloaded = _scan_downloaded_trace_ids(workspace_root, format)
        else:
            already_downloaded = _scan_downloaded_trace_ids(workspace_root, format)
        pending_ids = [tid for tid in trace_ids if tid not in already_downloaded]
        skipped_count = len(trace_ids) - len(pending_ids)
    else:
        pending_ids = trace_ids

    if debug:
        debug_print(
            f"Exporting {len(pending_ids)}/{len(trace_ids)} trace(s) "
            f"({skipped_count} already downloaded, skipping API calls for those)",
            debug,
        )

    if pending_ids:
        # Cache project names to avoid repeated API calls (shared across threads).
        project_name_cache: dict[str, str] = {}
        cache_lock = threading.Lock()

        with Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            BarColumn(),
            TaskProgressColumn(),
            console=console,
        ) as progress:
            task = progress.add_task(
                f"Exporting {len(pending_ids)} traces...", total=len(pending_ids)
            )

            for batch_start in range(0, len(pending_ids), BATCH_SIZE):
                batch_end = min(batch_start + BATCH_SIZE, len(pending_ids))
                batch_trace_ids = pending_ids[batch_start:batch_end]

                if debug:
                    debug_print(
                        f"Batch {batch_start // BATCH_SIZE + 1}: traces {batch_start + 1}-{batch_end}",
                        debug,
                    )

                # Fetch trace data in parallel
                fetched_traces: dict[str, Tuple[dict, str]] = {}

                with ThreadPoolExecutor(max_workers=MAX_WORKERS) as fetch_executor:
                    fetch_futures: Dict[
                        Future[Optional[Tuple[str, dict, str]]], str
                    ] = {}
                    for trace_id in batch_trace_ids:
                        fetch_future: Future[Optional[Tuple[str, dict, str]]] = (
                            fetch_executor.submit(
                                _fetch_trace_data,
                                client,
                                trace_id,
                                project_name_cache,
                                cache_lock,
                                debug,
                            )
                        )
                        fetch_futures[fetch_future] = trace_id

                    batch_filter_skipped = 0
                    batch_none_count = 0
                    for fetch_future in as_completed(fetch_futures):
                        trace_id = fetch_futures[fetch_future]
                        try:
                            result = fetch_future.result()
                            if result is not None:
                                fetched_trace_id, trace_data, project_name = result
                                if filter_string and not matches_trace_filter(
                                    trace_data.get("trace", {}), filter_string
                                ):
                                    skipped_count += 1
                                    batch_filter_skipped += 1
                                    continue
                                fetched_traces[fetched_trace_id] = (
                                    trace_data,
                                    project_name,
                                )
                            else:
                                # _fetch_trace_data returns None for unresolvable
                                # traces (e.g. missing project_id).  Treat as skipped
                                # rather than failed so manifest.complete() isn't
                                # permanently blocked by permanently-unresolvable IDs.
                                skipped_count += 1
                                batch_none_count += 1
                        except Exception as e:
                            console.print(
                                f"[red]Error fetching trace {trace_id}: {e}[/red]"
                            )

                # Count true fetch failures: exclude filter-skipped and unresolvable
                # (None-result) traces — only actual exceptions are real failures.
                batch_fetch_failures = (
                    len(batch_trace_ids)
                    - len(fetched_traces)
                    - batch_filter_skipped
                    - batch_none_count
                )
                failed_count += batch_fetch_failures

                # Write files in parallel
                with ThreadPoolExecutor(max_workers=MAX_WORKERS) as write_executor:
                    write_futures: Dict[Future[bool], str] = {}
                    for trace_id, (trace_data, project_name) in fetched_traces.items():
                        write_future: Future[bool] = write_executor.submit(
                            _write_trace_file,
                            trace_id,
                            trace_data,
                            project_name,
                            workspace_root,
                            format,
                            force,
                            debug,
                        )
                        write_futures[write_future] = trace_id

                    for write_future in as_completed(write_futures):
                        trace_id = write_futures[write_future]
                        try:
                            if write_future.result():
                                exported_count += 1
                                if manifest:
                                    manifest.mark_trace_downloaded(trace_id)
                            else:
                                skipped_count += 1
                        except Exception as e:
                            failed_count += 1
                            if debug:
                                console.print(
                                    f"[red]Error writing trace {trace_id}: {e}[/red]"
                                )
                        finally:
                            progress.update(
                                task,
                                advance=1,
                                description=f"Exported {exported_count}/{len(pending_ids)} traces",
                            )

                    # Update progress for traces that failed to fetch
                    for trace_id in batch_trace_ids:
                        if trace_id not in fetched_traces:
                            progress.update(task, advance=1)

    # Mark manifest complete only when there were no fetch/write failures,
    # so interrupted exports resume correctly on the next run.
    if manifest and failed_count == 0:
        manifest.complete(export_start_time)

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
    experiment_obj: Optional[Any] = None,
) -> tuple[Dict[str, int], int, Optional[ExportManifest]]:
    """Export a specific experiment by ID, including related datasets and traces.

    Args:
        experiment_obj: Optional pre-fetched experiment object. When provided the
            ``client.get_experiment_by_id`` call is skipped, saving one API round-trip.

    Returns:
        Tuple of (stats dictionary, file_written flag, manifest) where:
        - stats: Dictionary with keys "datasets", "prompts", "traces" and their counts
        - file_written: 1 if experiment file was written, 0 if skipped or error
        - manifest: ExportManifest for this experiment (pass to export_traces_by_ids)
    """
    try:
        # ------------------------------------------------------------------
        # Per-experiment manifest (Phase 2: skip get_items() on re-run)
        # ------------------------------------------------------------------
        output_dir.mkdir(parents=True, exist_ok=True)
        manifest = ExportManifest(output_dir, filename=f"manifest_{experiment_id}.db")

        # Reset on --force or format change.
        if force:
            manifest.reset()
        elif manifest.get_format() and manifest.get_format() != format:
            console.print(
                f"[yellow]Export format changed ({manifest.get_format()} → {format}), "
                f"resetting manifest for experiment {experiment_id}[/yellow]"
            )
            manifest.reset()

        # Fast path: if the manifest already has stored trace IDs AND the experiment
        # JSON file exists on disk, skip all API calls (get_experiment_by_id +
        # get_items).  This covers both completed and in_progress manifest states —
        # e.g. a previous run that fetched items but crashed before finishing traces.
        if not force:
            stored_ids = manifest.get_all_trace_ids()
            experiment_files = list(
                output_dir.glob(f"experiment_*_{experiment_id}.json")
            )
            if stored_ids is not None and experiment_files:
                if trace_ids_collector is not None:
                    trace_ids_collector.update(stored_ids)
                debug_print(
                    f"Manifest has stored IDs + file exists: skipping API calls for "
                    f"experiment {experiment_id} ({len(stored_ids)} trace IDs cached)",
                    debug,
                )
                _empty: Dict[str, int] = {
                    "datasets": 0,
                    "datasets_skipped": 0,
                    "prompts": 0,
                    "prompts_skipped": 0,
                    "traces": 0,
                    "traces_skipped": len(stored_ids),
                }
                return (_empty, 0, manifest)
            elif stored_ids is None and experiment_files:
                # JSON exists but manifest lacks stored IDs (e.g. exported before
                # the per-experiment manifest feature, or a run that was interrupted
                # before store_all_trace_ids was called).  Extract trace IDs directly
                # from the JSON file to avoid a get_items() API round-trip.
                try:
                    with open(experiment_files[0]) as _fh:
                        _exp_data = json.load(_fh)
                    trace_ids_from_json = [
                        item.get("trace_id")
                        for item in _exp_data.get("items", [])
                        if item.get("trace_id")
                    ]
                    manifest.store_all_trace_ids(trace_ids_from_json)
                    manifest.start(format)
                    if trace_ids_collector is not None:
                        trace_ids_collector.update(trace_ids_from_json)
                    debug_print(
                        f"Read {len(trace_ids_from_json)} trace IDs from existing JSON "
                        f"for experiment {experiment_id}; skipping get_items() API call",
                        debug,
                    )
                    _empty2: Dict[str, int] = {
                        "datasets": 0,
                        "datasets_skipped": 0,
                        "prompts": 0,
                        "prompts_skipped": 0,
                        "traces": 0,
                        "traces_skipped": len(trace_ids_from_json),
                    }
                    return (_empty2, 0, manifest)
                except (OSError, json.JSONDecodeError) as _e:
                    import traceback

                    console.print(
                        f"[yellow]Warning: Could not read trace IDs from JSON for "
                        f"experiment {experiment_id}: {_e}; "
                        f"falling back to get_items() API call[/yellow]"
                    )
                    debug_print(
                        f"Traceback: {traceback.format_exc()}",
                        debug,
                    )
                    # Fall through to full API path
            elif stored_ids is not None and not experiment_files:
                # Manifest has IDs but the experiment file was deleted.
                # Reset and fall through to a full re-export.
                debug_print(
                    f"Manifest has stored IDs but experiment file missing for "
                    f"{experiment_id}; resetting manifest and re-exporting.",
                    debug,
                )
                manifest.reset()

        if experiment_obj is not None:
            experiment = experiment_obj
            debug_print(f"Using pre-fetched experiment: {experiment.name}", debug)
        else:
            console.print(f"[blue]Fetching experiment by ID: {experiment_id}[/blue]")
            experiment = client.get_experiment_by_id(experiment_id)
            if not experiment:
                console.print(f"[red]Experiment '{experiment_id}' not found[/red]")
                return ({"datasets": 0, "prompts": 0, "traces": 0}, 0, None)

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
        stats: Dict[str, int] = {
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

        # Collect trace IDs from experiment items (for batch export later).
        # Store them in the manifest so future runs can skip get_items().
        trace_ids = [item.trace_id for item in experiment_items if item.trace_id]
        manifest.store_all_trace_ids(trace_ids)
        manifest.start(format)

        if trace_ids_collector is not None:
            trace_ids_collector.update(trace_ids)

        # Traces are exported at batch level, so we don't export them here
        stats["traces"] = 0
        stats["traces_skipped"] = 0

        if debug:
            console.print(
                f"[green]Experiment {experiment.name} exported with stats: {stats}[/green]"
            )

        # Return stats dictionary, whether file was written, and the manifest
        return (stats, 1 if experiment_file_written else 0, manifest)

    except Exception as e:
        console.print(f"[red]Error exporting experiment {experiment_id}: {e}[/red]")
        # Return empty stats and 0 for file written on error
        return ({"datasets": 0, "prompts": 0, "traces": 0}, 0, None)


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
    filter_string: Optional[str] = None,
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

        # Filter experiments by dataset if specified (client-side filtering)
        if dataset:
            experiments = [exp for exp in experiments if exp.dataset_name == dataset]
            if not experiments:
                console.print(
                    f"[yellow]No experiments found with name '{name}' using dataset '{dataset}'[/yellow]"
                )
                return
            if debug:
                debug_print(
                    f"Filtered to {len(experiments)} experiment(s) using dataset '{dataset}'",
                    debug,
                )

        # Collect all unique resources from all experiments first
        unique_datasets = set()
        unique_prompt_ids: set[str] = set()

        # First pass: collect datasets and prompt IDs (these are available without fetching items)
        for experiment in experiments:
            if experiment.dataset_name:
                unique_datasets.add(experiment.dataset_name)
            # Get experiment data to access prompt_versions
            experiment_data = experiment.get_experiment_data()
            if experiment_data.prompt_versions:
                for prompt_version in experiment_data.prompt_versions:
                    if prompt_version.prompt_id:
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

        # Track manifests per experiment so we can pass the right one to
        # export_traces_by_ids() (optimisation applies cleanly to single-experiment exports).
        experiment_manifests: Dict[str, Optional[ExportManifest]] = {}

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
                experiment_obj=experiment,
            )

            # result is a tuple: (stats_dict, file_written_flag, manifest)
            exp_stats, file_written, exp_manifest = result
            experiment_manifests[experiment.id] = exp_manifest
            # Aggregate stats (only related prompts, traces already handled)
            aggregated_stats["prompts"] += exp_stats.get("prompts", 0)
            aggregated_stats["prompts_skipped"] += exp_stats.get("prompts_skipped", 0)

            if file_written > 0:
                exported_count += 1
            else:
                skipped_count += 1

        # Export all unique traces once after collecting them from all experiments.
        # For a single experiment we pass its manifest so Phase 2 can skip traces
        # that are already in the downloaded set without touching the filesystem.
        # For multiple experiments we pass None and rely on Phase 1 (filesystem scan).
        workspace_root = output_dir.parent
        trace_manifest: Optional[ExportManifest] = (
            experiment_manifests.get(experiments[0].id)
            if len(experiments) == 1
            else None
        )
        traces_exported, traces_skipped = export_collected_trace_ids(
            client,
            workspace_root,
            all_trace_ids,
            max_traces,
            format,
            debug,
            force,
            manifest=trace_manifest,
            filter_string=filter_string,
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
    filter_string: Optional[str] = None,
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

            exp_stats, file_written, exp_manifest = result

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

            # Export traces collected from experiment items, passing the manifest
            # so already-downloaded traces are skipped before any API call.
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
                        manifest=exp_manifest,
                        filter_string=filter_string,
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
            filter_string=filter_string,
        )

    except Exception as e:
        console.print(f"[red]Error exporting experiment: {e}[/red]")
        sys.exit(1)


@click.command(name="experiment")
@click.argument("name_or_id", type=str)
@click.option(
    "--filter",
    type=str,
    help="OQL filter string applied to traces (e.g. 'created_at >= \"2024-01-01T00:00:00Z\"').",
)
@click.option(
    "--dataset",
    type=str,
    help="Filter experiments by dataset name. Only experiments using this dataset will be exported.",
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
    default="opik_exports",
    help="Directory to save exported data. Defaults to opik_exports.",
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
    filter: Optional[str],
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
        name_or_id,
        workspace,
        path,
        dataset,
        max_traces,
        force,
        debug,
        format,
        api_key,
        filter_string=filter,
    )
