"""Project export functionality."""

import sys
import time
from concurrent.futures import ThreadPoolExecutor, as_completed
from contextlib import nullcontext
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Any, Optional, Set

import random

import click
import httpx
import tenacity
from rich.progress import Progress, SpinnerColumn, TextColumn

import opik
from opik.rest_api.core.api_error import ApiError
from opik.rest_api.core.http_client import _parse_retry_after
from opik.rest_api.types.project_public import ProjectPublic
from opik.rest_client_configurator import retry_decorator
from ..export_manifest import ExportManifest
from .utils import (
    console,
    debug_print,
    extract_trace_id_from_filename,
    matches_name_pattern,
    print_export_summary,
    trace_to_csv_rows,
    write_csv_data,
    write_json_data,
)

# Maximum number of concurrent workers for parallel span fetching.
# Keep low to avoid hitting server-side rate limits.
MAX_WORKERS = 3

# Delay (seconds) between fetching successive pages of traces.
_PAGE_FETCH_DELAY_SECONDS = 1.0

# If this many consecutive page fetches fail (after all per-request retries),
# abort the loop rather than endlessly skipping pages — something systemic is wrong.
MAX_CONSECUTIVE_PAGE_FAILURES = 5

# Export-specific retry: more attempts and longer waits than the default
# SDK decorator, to survive aggressive server-side rate limiting during
# bulk exports without aborting the whole operation.
_EXPORT_MAX_RETRY_AFTER_SECONDS = 120.0


def _parse_rate_limit_reset(headers: httpx.Headers) -> Optional[float]:
    """Extract seconds-until-reset from non-standard rate-limit headers.

    Checks (in order):
    1. ``opik-search-spans-remaining-limit-ttl-millis`` — ms until the spans
       search window resets (Opik-specific).
    2. ``ratelimit-reset`` — seconds until reset (draft IETF RateLimit header).
    """
    ttl_ms = headers.get("opik-search-spans-remaining-limit-ttl-millis")
    if ttl_ms is not None:
        try:
            return max(0.0, float(ttl_ms) / 1000)
        except (ValueError, TypeError):
            pass

    ratelimit_reset = headers.get("ratelimit-reset")
    if ratelimit_reset is not None:
        try:
            return max(0.0, float(ratelimit_reset))
        except (ValueError, TypeError):
            pass

    return None


def _export_wait_duration(retry_state: tenacity.RetryCallState) -> float:
    """Wait strategy for export retries.

    Honours the server's rate-limit headers for 429 responses (up to
    ``_EXPORT_MAX_RETRY_AFTER_SECONDS``).  Adds random jitter so that
    multiple concurrent workers don't all restart at the same instant
    ("thundering herd").  Falls back to a longer exponential backoff than
    the standard SDK decorator for other transient errors.
    """
    exc = retry_state.outcome.exception() if retry_state.outcome else None
    if isinstance(exc, ApiError) and exc.status_code == 429:
        headers = httpx.Headers(getattr(exc, "headers", {}) or {})
        # Prefer the standard Retry-After header, then fall back to
        # Opik-specific / draft-IETF rate-limit reset headers.
        seconds = _parse_retry_after(headers)
        if seconds is None or seconds > _EXPORT_MAX_RETRY_AFTER_SECONDS:
            seconds = _parse_rate_limit_reset(headers)
        if seconds is None or seconds > _EXPORT_MAX_RETRY_AFTER_SECONDS:
            seconds = 30.0
        # Jitter: spread concurrent workers across a 0-5 s window so they
        # don't all restart simultaneously and immediately exhaust the limit.
        jitter = random.uniform(0.0, 5.0)
        return max(0.0, seconds) + jitter
    # Other transient errors (e.g. connection blips): scale the base wait by
    # MAX_WORKERS so the combined retry pressure from all concurrent workers
    # does not keep the server saturated during recovery.
    # With MAX_WORKERS=3: ~6s, 12s, 24s, 48s, 60s (capped).
    base = tenacity.wait_exponential(multiplier=2, min=2, max=60)(retry_state)
    return min(base * MAX_WORKERS, 60.0)


_export_rest_retry = tenacity.retry(
    stop=tenacity.stop_after_attempt(8),
    wait=_export_wait_duration,
    retry=tenacity.retry_if_exception(retry_decorator._allowed_to_retry),
    reraise=True,
)


def _print_project_export_status(
    descriptor: str,
    output_dir: Path,
    exported_count: int,
    had_errors: bool,
) -> None:
    """Print a consistent post-export status line for a single project."""
    if had_errors:
        console.print(
            f"[yellow]Export of '{descriptor}' completed with errors — some traces may be "
            "missing. Run the export again to download any skipped traces.[/yellow]"
        )
    elif exported_count > 0:
        console.print(
            f"[green]Successfully exported project '{descriptor}' to {output_dir}[/green]"
        )
    else:
        console.print(
            f"[yellow]Project '{descriptor}' already up to date (use --force to re-download)[/yellow]"
        )


def _print_oql_examples() -> None:
    console.print("[yellow]OQL filter syntax examples:[/yellow]")
    console.print('  status = "running"')
    console.print('  name contains "test"')
    console.print('  start_time >= "2024-01-01T00:00:00Z"')
    console.print("  usage.total_tokens > 1000")
    console.print(
        "[yellow]Note: String values must be in double quotes, use = not :[/yellow]"
    )


def _validate_filter_syntax(filter_string: str) -> None:
    """Validate OQL filter syntax, printing user-friendly errors and raising ValueError."""
    from opik.api_objects import opik_query_language

    try:
        oql = opik_query_language.OpikQueryLanguage.for_traces(filter_string)
        if oql.get_filter_expressions() is None and filter_string.strip():
            console.print(
                f"[red]Error: Invalid filter syntax: Filter '{filter_string}' could not be parsed.[/red]"
            )
            _print_oql_examples()
            raise ValueError(
                f"Invalid filter syntax: '{filter_string}' could not be parsed"
            )
    except ValueError as e:
        if "Invalid filter syntax" in str(e) and "could not be parsed" in str(e):
            raise
        console.print(f"[red]Error: Invalid filter syntax: {e}[/red]")
        _print_oql_examples()
        raise


@_export_rest_retry
def _fetch_traces_page(
    client: opik.Opik,
    project_name: str,
    parsed_filter: Optional[str],
    page: int,
    page_size: int,
) -> Any:
    """Fetch one page of traces. Retries on 429/5xx via _export_rest_retry."""
    return client.rest_client.traces.get_traces_by_project(
        project_name=project_name,
        filters=parsed_filter,
        page=page,
        size=page_size,
        truncate=False,
    )


@_export_rest_retry
def _fetch_spans(
    client: opik.Opik,
    trace: Any,
    project_name: str,
) -> tuple:
    """Fetch spans for a single trace. Returns (trace, spans).

    Retries on transient errors (429, 5xx, network issues) via
    _export_rest_retry; re-raises on permanent failures.
    """
    return trace, client.search_spans(
        project_name=project_name,
        trace_id=trace.id,
        max_results=1000,
        truncate=False,
    )


def export_traces(
    client: opik.Opik,
    project_name: str,
    project_dir: Path,
    max_results: Optional[int],
    filter_string: Optional[str],
    project_name_filter: Optional[str] = None,
    format: str = "json",
    debug: bool = False,
    force: bool = False,
    show_progress: bool = True,
    manifest: Optional[ExportManifest] = None,
    page_size: int = 500,
) -> tuple[int, int, bool]:
    """Download traces and their spans with pagination support for large projects."""
    if debug:
        debug_print(
            f"DEBUG: _export_traces called with project_name: {project_name}, project_dir: {project_dir}",
            debug,
        )

    # Validate filter syntax before using it
    if filter_string and filter_string.strip():
        _validate_filter_syntax(filter_string)

    exported_count = 0
    skipped_count = 0
    had_errors = False
    # page_size is passed in; default is 500 (larger pages → fewer round-trips,
    # which offsets the concurrency reduction introduced to avoid rate limits)
    current_page = 1
    total_processed = 0
    consecutive_page_failures = 0

    # Build the set of already-downloaded trace IDs once, before the loop.
    # Manifest DB lookup is faster and survives aborted exports; filesystem
    # scan is the fallback when no manifest exists.
    ext = "csv" if format.lower() == "csv" else "json"
    if force:
        already_downloaded: Set[str] = set()
    elif manifest is not None:
        already_downloaded = manifest.load_downloaded_set()
        if debug:
            debug_print(
                f"DEBUG: Loaded {len(already_downloaded)} already-downloaded trace IDs from manifest",
                debug,
            )
    else:
        already_downloaded = {
            tid
            for p in project_dir.glob(f"trace_*.{ext}")
            if (tid := extract_trace_id_from_filename(p))
        }
        if debug:
            debug_print(
                f"DEBUG: Scanned directory: {len(already_downloaded)} already-downloaded traces",
                debug,
            )

    _progress_cm = (
        Progress(
            SpinnerColumn(),
            TextColumn("[progress.description]{task.description}"),
            console=console,
        )
        if show_progress
        else nullcontext()
    )
    with _progress_cm as progress:
        task = (
            progress.add_task("Searching for traces...", total=None)
            if progress
            else None
        )

        while max_results is None or total_processed < max_results:
            # Calculate how many traces to fetch in this batch
            if max_results is not None:
                remaining = max_results - total_processed
                current_page_size = min(page_size, remaining)
            else:
                current_page_size = page_size

            if debug:
                debug_print(
                    f"DEBUG: Getting traces by project with project_name: {project_name}, filter: {filter_string}, page: {current_page}, size: {current_page_size}",
                    debug,
                )

            # Parse filter to JSON format - backend expects JSON, not OQL string
            parsed_filter = None
            if filter_string:
                from opik.api_objects import opik_query_language

                oql = opik_query_language.OpikQueryLanguage.for_traces(filter_string)
                parsed_filter = oql.parsed_filters  # This is the JSON string
                if debug:
                    debug_print(f"DEBUG: Parsed filter to JSON: {parsed_filter}", debug)

            try:
                # _fetch_traces_page retries on 429/5xx via _export_rest_retry.
                trace_page = _fetch_traces_page(
                    client, project_name, parsed_filter, current_page, current_page_size
                )
            except (
                ApiError,
                httpx.RemoteProtocolError,
                httpx.ConnectError,
                httpx.TimeoutException,
            ) as e:
                # Re-raise permanent API errors (e.g. 400, 401, 403) — these indicate
                # a misconfiguration and skipping the page would silently hide the problem.
                if (
                    isinstance(e, ApiError)
                    and e.status_code not in retry_decorator.RETRYABLE_STATUS_CODES
                ):
                    raise
                # Transient error that exhausted all retries — skip this page.
                consecutive_page_failures += 1
                if consecutive_page_failures >= MAX_CONSECUTIVE_PAGE_FAILURES:
                    console.print(
                        f"[red]{consecutive_page_failures} consecutive page fetch failures — "
                        "aborting export. Run again to resume.[/red]"
                    )
                    had_errors = True
                    break
                console.print(
                    f"[red]Error fetching page {current_page}: {e} — skipping page and continuing.[/red]"
                )
                had_errors = True
                current_page += 1
                time.sleep(_PAGE_FETCH_DELAY_SECONDS)
                continue

            consecutive_page_failures = 0
            traces = trace_page.content or []
            if debug:
                debug_print(
                    f"DEBUG: Found {len(traces)} traces in project (page {current_page})",
                    debug,
                )

            if not traces:
                # No more traces to process
                break

            # Fast-path: on the first page, compare the API's total trace count
            # against how many we already have.  If the API reports no more traces
            # than we've already downloaded, every trace is present locally and we
            # can skip scanning the remaining pages entirely.
            # Skip this optimisation when a filter is active: api_total then
            # reflects only filtered traces, while already_downloaded may contain
            # traces from a prior unfiltered export, making the comparison
            # unreliable and causing new filtered traces to be silently missed.
            if (
                current_page == 1
                and not force
                and already_downloaded
                and not filter_string
            ):
                api_total = getattr(trace_page, "total", None)
                if api_total is not None and api_total <= len(already_downloaded):
                    skipped_count = api_total
                    if debug:
                        debug_print(
                            f"DEBUG: Fast-path exit — API total={api_total}, "
                            f"already_downloaded={len(already_downloaded)}; "
                            "no new traces to fetch",
                            debug,
                        )
                    break

            if progress and task is not None:
                progress.update(
                    task,
                    description=f"Downloading traces... (page {current_page}, {len(traces)} traces)",
                )

            # Filter traces by project name if specified
            if project_name_filter:
                original_count = len(traces)
                traces = [
                    trace
                    for trace in traces
                    if matches_name_pattern(trace.name or "", project_name_filter)
                ]
                if len(traces) < original_count:
                    console.print(
                        f"[blue]Filtered to {len(traces)} traces matching project '{project_name_filter}' in current batch[/blue]"
                    )

            if not traces:
                # No traces match the name pattern, but we might have more to process
                # Use original_traces for pagination, not the filtered empty list
                total_processed += current_page_size
                continue

            if progress and task is not None:
                progress.update(
                    task,
                    description=f"Downloading traces... (batch {total_processed // page_size + 1})",
                )

            # Step 1: Check the already-downloaded set (O(1) per trace).
            # Also verify the file still exists on disk — a stale manifest entry
            # for a deleted file would otherwise skip a trace forever.
            traces_to_fetch = []
            for trace in traces:
                if trace.id in already_downloaded:
                    file_path = project_dir / f"trace_{trace.id}.{ext}"
                    if file_path.exists():
                        if debug:
                            debug_print(
                                f"Skipping trace {trace.id} (already downloaded)", debug
                            )
                        skipped_count += 1
                        total_processed += 1
                    else:
                        # File was deleted after the manifest was written — re-download.
                        if debug:
                            debug_print(
                                f"Trace {trace.id} in manifest but file missing; re-downloading",
                                debug,
                            )
                        already_downloaded.discard(trace.id)
                        traces_to_fetch.append(trace)
                else:
                    traces_to_fetch.append(trace)

            # Step 2: Fetch spans in parallel for traces that need downloading.
            if traces_to_fetch:
                with ThreadPoolExecutor(max_workers=MAX_WORKERS) as executor:
                    future_to_trace = {
                        executor.submit(
                            _fetch_spans, client, trace, project_name
                        ): trace
                        for trace in traces_to_fetch
                    }
                    for future in as_completed(future_to_trace):
                        trace = future_to_trace[future]
                        try:
                            _, spans = future.result()
                            trace_data = {
                                "trace": trace.model_dump(),
                                "spans": [span.model_dump() for span in spans],
                                "downloaded_at": datetime.now().isoformat(),
                                "project_name": project_name,
                            }
                            file_path = project_dir / f"trace_{trace.id}.{ext}"
                            try:
                                if format.lower() == "csv":
                                    write_csv_data(
                                        trace_data, file_path, trace_to_csv_rows
                                    )
                                    if debug:
                                        debug_print(
                                            f"Wrote CSV file: {file_path}", debug
                                        )
                                else:
                                    write_json_data(trace_data, file_path)
                                    if debug:
                                        debug_print(
                                            f"Wrote JSON file: {file_path}", debug
                                        )
                                exported_count += 1
                                total_processed += 1
                                # Track in manifest and local set so subsequent
                                # pages don't re-download the same trace.
                                already_downloaded.add(trace.id)
                                if manifest is not None:
                                    manifest.mark_trace_downloaded(trace.id)
                            except Exception as write_error:
                                console.print(
                                    f"[red]Error writing trace {trace.id} to file: {write_error}[/red]"
                                )
                                had_errors = True
                                if debug:
                                    import traceback

                                    debug_print(
                                        f"Traceback: {traceback.format_exc()}", debug
                                    )
                        except Exception as e:
                            console.print(
                                f"[red]Error exporting trace {trace.id}: {e}[/red]"
                            )
                            had_errors = True

            # Update pagination for next iteration
            if traces:
                current_page += 1
            else:
                # No more traces to process
                break

            # If we got fewer traces than requested, we've reached the end
            if len(traces) < current_page_size:
                break

            # Throttle page fetches to avoid triggering server-side rate limits.
            time.sleep(_PAGE_FETCH_DELAY_SECONDS)

        # Final progress update
        if exported_count == 0 and skipped_count == 0:
            console.print("[yellow]No traces found in the project.[/yellow]")
        elif progress and task is not None:
            progress.update(task, description=f"Exported {exported_count} traces total")

    return exported_count, skipped_count, had_errors


def export_project_by_name(
    name: str,
    workspace: str,
    output_path: str,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
    page_size: int = 500,
) -> None:
    """Export a project by exact name."""
    try:
        if debug:
            debug_print(f"Exporting project: {name}", debug)

        # Validate filter syntax before doing anything else
        if filter_string and filter_string.strip():
            _validate_filter_syntax(filter_string)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "projects"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Get projects and find exact match
        # Use name filter to narrow down results (backend does partial match, case-insensitive)
        # Then verify exact match on client side
        projects_response = client.rest_client.projects.find_projects(name=name)
        projects = projects_response.content or []
        matching_project = None

        for project in projects:
            if project.name == name:
                matching_project = project
                break

        # If not found with name filter, try paginating through all projects as fallback
        # This handles edge cases where the name filter might not work as expected
        if not matching_project:
            if debug:
                debug_print(
                    f"Project '{name}' not found in filtered results, searching all projects...",
                    debug,
                )
            # Paginate through all projects
            page = 1
            page_size = 100
            while True:
                projects_response = client.rest_client.projects.find_projects(
                    page=page, size=page_size
                )
                projects = projects_response.content or []
                if not projects:
                    break

                for project in projects:
                    if project.name == name:
                        matching_project = project
                        break

                if matching_project:
                    break

                # Check if there are more pages
                if projects_response.total is not None:
                    total_pages = (projects_response.total + page_size - 1) // page_size
                    if page >= total_pages:
                        break
                elif len(projects) < page_size:
                    # No more pages if we got fewer results than page size
                    break

                page += 1

        if not matching_project:
            console.print(f"[red]Project '{name}' not found[/red]")
            sys.exit(1)

        if debug:
            debug_print(f"Found project by exact match: {matching_project.name}", debug)

        # Export the project
        exported_count, traces_exported, traces_skipped, had_errors = (
            export_single_project(
                client,
                matching_project,
                output_dir,
                filter_string,
                max_results,
                force,
                debug,
                format,
                page_size=page_size,
            )
        )

        # Collect statistics for summary using actual export counts
        stats = {
            "projects": 1 if exported_count > 0 else 0,
            "projects_skipped": 0 if exported_count > 0 else 1,
            "traces": traces_exported,
            "traces_skipped": traces_skipped,
        }

        # Show export summary
        print_export_summary(stats, format)

        _print_project_export_status(name, output_dir, exported_count, had_errors)

    except ValueError as e:
        # Filter validation errors are already formatted, just exit
        if "Invalid filter syntax" in str(e):
            sys.exit(1)
        # Other ValueErrors should be shown
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)
    except Exception as e:
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)


def export_single_project(
    client: opik.Opik,
    project: ProjectPublic,
    output_dir: Path,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    show_progress: bool = True,
    page_size: int = 500,
) -> tuple[int, int, int, bool]:
    """Export a single project."""
    try:
        # Create project-specific directory for traces
        project_traces_dir = output_dir / project.name
        project_traces_dir.mkdir(parents=True, exist_ok=True)

        # Record the export start time BEFORE the first API call so that any
        # trace created while the export runs is included in the next run's window.
        export_start_time = datetime.now(timezone.utc).isoformat()

        # Manifest lifecycle
        manifest = ExportManifest(project_traces_dir)

        if force:
            if ExportManifest.exists(project_traces_dir):
                manifest.reset()
                if debug:
                    debug_print("DEBUG: --force: discarding existing manifest", debug)
        else:
            manifest_format = manifest.get_format()
            if manifest_format and manifest_format != format:
                # Format changed — the manifest tracks the wrong file type; start fresh.
                console.print(
                    f"[yellow]Export format changed ({manifest_format} → {format}); "
                    "discarding existing manifest and re-downloading.[/yellow]"
                )
                manifest.reset()
            elif manifest.is_completed:
                last_exported_at = manifest.get_last_exported_at()
                if last_exported_at and debug:
                    debug_print(
                        f"DEBUG: Previous export completed at {last_exported_at}; "
                        "using incremental filter",
                        debug,
                    )
            elif manifest.is_in_progress:
                completed = manifest.downloaded_count()
                console.print(
                    f"[yellow]Resuming interrupted export "
                    f"({completed} trace(s) already downloaded).[/yellow]"
                )
            else:
                # Brand-new manifest (status = not_started).  Trace files may already
                # exist on disk from a previous run that predates the manifest feature.
                # Seed the manifest from the filesystem so those files aren't re-downloaded.
                ext = "csv" if format.lower() == "csv" else "json"
                existing = list(project_traces_dir.glob(f"trace_*.{ext}"))
                if existing:
                    if debug:
                        debug_print(
                            f"DEBUG: Seeding manifest from {len(existing)} existing trace files",
                            debug,
                        )
                    for p in existing:
                        trace_id = extract_trace_id_from_filename(p)
                        if trace_id:
                            manifest.mark_trace_downloaded(trace_id)
                    manifest.save()
                    # Mark as completed using the newest file's mtime as the cutoff.
                    # This lets the incremental filter kick in for the current run too,
                    # avoiding a full API page-scan when all traces are already on disk.
                    newest_mtime = max(p.stat().st_mtime for p in existing)
                    seed_time = datetime.fromtimestamp(
                        newest_mtime, tz=timezone.utc
                    ).isoformat()
                    manifest.complete(seed_time)
                    if debug:
                        debug_print(
                            f"DEBUG: Seeded manifest from filesystem; marking completed "
                            f"with cutoff {seed_time}",
                            debug,
                        )

        # Build incremental filter when the previous export completed successfully.
        # Use created_at (set by the backend at ingestion) rather than start_time
        # (set by the SDK) because created_at is monotonically increasing.
        effective_filter = filter_string
        if not force and manifest.is_completed:
            last_exported_at = manifest.get_last_exported_at()
            if last_exported_at:
                try:
                    cutoff_dt = datetime.fromisoformat(last_exported_at) - timedelta(
                        minutes=5
                    )
                    cutoff_str = cutoff_dt.strftime("%Y-%m-%dT%H:%M:%SZ")
                    incremental_clause = f'created_at >= "{cutoff_str}"'
                    effective_filter = (
                        f"{filter_string} AND {incremental_clause}"
                        if filter_string
                        else incremental_clause
                    )
                    if debug:
                        debug_print(
                            f"DEBUG: Incremental filter: {incremental_clause}", debug
                        )
                except (ValueError, TypeError) as e:
                    if debug:
                        debug_print(
                            f"DEBUG: Could not parse last_exported_at '{last_exported_at}': {e}; "
                            "falling back to full fetch",
                            debug,
                        )

        manifest.start(format)

        # Export related traces for this project
        traces_exported, traces_skipped, traces_had_errors = export_traces(
            client,
            project.name,
            project_traces_dir,
            max_results,  # None means no limit — fetch all pages
            effective_filter,
            None,  # project_name_filter
            format,
            debug,
            force,
            show_progress,
            manifest,
            page_size,
        )

        # Only mark the manifest complete when the export finished without errors.
        # Leaving it in_progress on failure means the next run resumes from where
        # this one left off rather than skipping traces that were never written.
        if not traces_had_errors:
            manifest.complete(export_start_time)

        # Project export only exports traces - datasets and prompts must be exported separately
        project_exported = 1 if (traces_exported > 0 or traces_skipped > 0) else 0
        if traces_exported > 0:
            if debug:
                debug_print(
                    f"Exported project: {project.name} with {traces_exported} traces",
                    debug,
                )
        elif traces_skipped > 0:
            if debug:
                debug_print(
                    f"Project {project.name} already has {traces_skipped} trace files",
                    debug,
                )
        else:
            if debug:
                debug_print(
                    f"No traces found in project: {project.name}",
                    debug,
                )
        return (project_exported, traces_exported, traces_skipped, traces_had_errors)

    except Exception as e:
        console.print(f"[red]Error exporting project {project.name}: {e}[/red]")
        return (0, 0, 0, True)


def export_project_by_name_or_id(
    name_or_id: str,
    workspace: str,
    output_path: str,
    filter_string: Optional[str],
    max_results: Optional[int],
    force: bool,
    debug: bool,
    format: str,
    api_key: Optional[str] = None,
    page_size: int = 500,
) -> None:
    """Export a project by name or ID.

    First tries to get the project by ID. If not found, tries by name.
    """
    try:
        if debug:
            debug_print(f"Attempting to export project: {name_or_id}", debug)

        # Initialize client
        if api_key:
            client = opik.Opik(api_key=api_key, workspace=workspace)
        else:
            client = opik.Opik(workspace=workspace)

        # Create output directory
        output_dir = Path(output_path) / workspace / "projects"
        output_dir.mkdir(parents=True, exist_ok=True)

        if debug:
            debug_print(f"Target directory: {output_dir}", debug)

        # Try to get project by ID first
        try:
            if debug:
                debug_print(f"Trying to get project by ID: {name_or_id}", debug)
            project = client.get_project(name_or_id)

            # Successfully found by ID, export it
            if debug:
                debug_print(
                    f"Found project by ID: {project.name} (ID: {project.id})", debug
                )

            # Export the project
            exported_count, traces_exported, traces_skipped, had_errors = (
                export_single_project(
                    client,
                    project,
                    output_dir,
                    filter_string,
                    max_results,
                    force,
                    debug,
                    format,
                    page_size=page_size,
                )
            )

            # Show export summary
            stats = {
                "projects": exported_count,
                "traces": traces_exported,
                "traces_skipped": traces_skipped,
            }
            print_export_summary(stats, format)

            _print_project_export_status(
                f"{project.name} (ID: {project.id})",
                output_dir,
                exported_count,
                had_errors,
            )
            return

        except ApiError as e:
            # Check if it's a 404 (not found) error
            if e.status_code == 404:
                # Not found by ID, try by name
                if debug:
                    debug_print(
                        f"Project not found by ID, trying by name: {name_or_id}", debug
                    )
                # Fall through to name-based export
                pass
            else:
                # Some other API error, re-raise it
                raise

        # Try by name (either because ID lookup failed or we're explicitly trying name)
        export_project_by_name(
            name_or_id,
            workspace,
            output_path,
            filter_string,
            max_results,
            force,
            debug,
            format,
            api_key,
            page_size,
        )

    except Exception as e:
        console.print(f"[red]Error exporting project: {e}[/red]")
        sys.exit(1)


@click.command(name="project")
@click.argument("name_or_id", type=str)
@click.option(
    "--filter",
    type=str,
    help="Filter string to narrow down traces using Opik Query Language (OQL).",
)
@click.option(
    "--max-results",
    type=int,
    help="Maximum number of traces to export. Limits the total number of traces downloaded.",
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
@click.option(
    "--page-size",
    type=click.IntRange(1, 1000),
    default=500,
    show_default=True,
    help="Number of traces to fetch per API request. Larger values reduce round-trips but increase memory usage.",
)
@click.pass_context
def export_project_command(
    ctx: click.Context,
    name_or_id: str,
    filter: Optional[str],
    max_results: Optional[int],
    path: str,
    force: bool,
    debug: bool,
    format: str,
    page_size: int,
) -> None:
    """Export a project by name or ID to workspace/projects.

    The command will first try to find the project by ID. If not found, it will try by name.
    """
    # Get workspace and API key from context
    workspace = ctx.obj["workspace"]
    api_key = ctx.obj.get("api_key") if ctx.obj else None
    export_project_by_name_or_id(
        name_or_id,
        workspace,
        path,
        filter,
        max_results,
        force,
        debug,
        format,
        api_key,
        page_size,
    )
