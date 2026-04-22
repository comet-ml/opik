"""Project import functionality."""

import json
import logging
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional

import opik
from opik import id_helpers
from rich.console import Console

from .._attachment_path import safe_attachment_path
from ..migration_manifest import MigrationManifest
from .experiment import recreate_experiments
from .utils import (
    matches_name_pattern,
    clean_feedback_scores,
    clean_usage_for_import,
    sort_spans_topologically,
    debug_print,
    build_import_metadata,
    _TRACE_IMPORT_FIELDS,
    _SPAN_IMPORT_FIELDS,
)

console = Console()
LOGGER = logging.getLogger(__name__)


def _upload_attachments_for_trace(
    client: opik.Opik,
    project_dir: Path,
    attachments: list,
    new_trace_id: str,
    span_id_map: Dict[str, str],
    project_name: str,
) -> None:
    """Queue attachment uploads for an imported trace via the background streamer.

    *project_dir* is the per-project directory that contains the
    ``attachments/<entity_type>/<original_entity_id>/<file_name>`` tree
    created during export.

    ID translation:
    - trace attachments  → queued against *new_trace_id*
    - span attachments   → looked up in *span_id_map*; skipped with a
                           warning when the mapping is missing.

    Uploads are non-blocking; call ``client.flush()`` after all traces for a
    project are processed to wait for completion. The streamer handles retries
    and monitoring automatically.
    """
    for att in attachments:
        entity_type = att.get("entity_type")
        original_entity_id = att.get("entity_id")
        file_name = att.get("file_name")
        mime_type = att.get("mime_type")

        if not all([entity_type, original_entity_id, file_name]):
            continue

        new_entity_id: Optional[str]
        if entity_type == "trace":
            new_entity_id = new_trace_id
        elif entity_type == "span":
            new_entity_id = span_id_map.get(original_entity_id)
            if not new_entity_id:
                console.print(
                    f"[yellow]Warning: span {original_entity_id} not in span map; "
                    f"skipping attachment '{file_name}'[/yellow]"
                )
                continue
        else:
            continue

        # Use the same basename normalisation as the exporter so that files
        # saved as e.g. "img.png" (from "dir/img.png") are found correctly.
        file_path = safe_attachment_path(
            base_dir=project_dir,
            entity_type=entity_type,
            entity_id=original_entity_id,
            file_name=file_name,
        )
        if file_path is None:
            console.print(
                f"[yellow]Warning: attachment '{file_name}' has an invalid or unsafe "
                "path; skipping[/yellow]"
            )
            continue

        if not file_path.exists():
            continue

        client.queue_attachment_upload(
            entity_type=entity_type,
            entity_id=new_entity_id,
            project_name=project_name,
            file_path=str(file_path),
            file_name=file_name,
            mime_type=mime_type,
        )


def import_projects_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments_flag: bool = False,
    manifest: Optional[MigrationManifest] = None,
    include_attachments: bool = True,
) -> Dict[str, int]:
    """Import projects from a directory.

    Returns:
        Dictionary with keys: 'projects', 'projects_skipped', 'projects_errors', 'traces', 'traces_errors'
    """
    try:
        project_dirs = [d for d in source_dir.iterdir() if d.is_dir()]

        if not project_dirs:
            console.print("[yellow]No project directories found[/yellow]")
            return {
                "projects": 0,
                "projects_skipped": 0,
                "projects_errors": 0,
                "traces": 0,
                "traces_errors": 0,
            }

        imported_count = 0
        skipped_count = 0
        error_count = 0
        total_traces_imported = 0
        total_traces_errors = 0

        # Seed trace_id_map from the manifest so experiments can cross-reference
        # traces that were imported in a previous (interrupted) run.
        trace_id_map: Dict[str, str] = manifest.get_trace_id_map() if manifest else {}

        for project_dir in project_dirs:
            try:
                project_name = project_dir.name

                # Filter by name pattern if specified
                if name_pattern and not matches_name_pattern(
                    project_name, name_pattern
                ):
                    debug_print(
                        f"Skipping project {project_name} (doesn't match pattern)",
                        debug,
                    )
                    skipped_count += 1
                    continue

                if dry_run:
                    console.print(f"[blue]Would import project: {project_name}[/blue]")
                    imported_count += 1
                    continue

                debug_print(f"Importing project: {project_name}", debug)

                # Per-project span ID map (only needed during this import session,
                # not persisted — span IDs are not referenced across files).
                span_id_map: Dict[str, str] = {}

                trace_files = list(project_dir.glob("trace_*.json"))
                traces_imported = 0
                traces_errors = 0

                for trace_file in trace_files:
                    try:
                        # Skip trace files already imported in a previous run
                        if manifest and manifest.is_file_completed(trace_file):
                            debug_print(
                                f"Skipping {trace_file.name} (already imported in a previous run)",
                                debug,
                            )
                            traces_imported += 1
                            continue

                        with open(trace_file, "r", encoding="utf-8") as f:
                            trace_data = json.load(f)

                        # Import trace and spans
                        trace_info = trace_data.get("trace", {})
                        spans_info = trace_data.get("spans", [])
                        original_trace_id = trace_info.get("id")

                        # Clean feedback scores to remove read-only fields
                        feedback_scores = clean_feedback_scores(
                            trace_info.get("feedback_scores")
                        )

                        original_start_time = (
                            datetime.fromisoformat(
                                trace_info["start_time"].replace("Z", "+00:00")
                            )
                            if trace_info.get("start_time")
                            else None
                        )

                        trace = client.trace(
                            id=id_helpers.generate_id(timestamp=original_start_time),
                            name=trace_info.get("name", "imported_trace"),
                            start_time=original_start_time,
                            end_time=(
                                datetime.fromisoformat(
                                    trace_info["end_time"].replace("Z", "+00:00")
                                )
                                if trace_info.get("end_time")
                                else None
                            ),
                            input=trace_info.get("input", {}),
                            output=trace_info.get("output", {}),
                            metadata=build_import_metadata(
                                trace_info,
                                _TRACE_IMPORT_FIELDS,
                                trace_info.get("metadata"),
                            ),
                            tags=trace_info.get("tags"),
                            feedback_scores=feedback_scores,
                            error_info=trace_info.get("error_info"),
                            thread_id=trace_info.get("thread_id"),
                            project_name=project_name,
                        )

                        if original_trace_id:
                            trace_id_map[original_trace_id] = trace.id
                            # Persist the mapping so it survives if we are interrupted
                            # before completing all files.
                            if manifest:
                                manifest.add_trace_mapping(original_trace_id, trace.id)

                        # Create spans with full data, preserving parent-child relationships.
                        # Sort spans topologically to ensure parents are processed before children
                        # so that multi-level hierarchies are handled correctly.
                        sorted_spans = sort_spans_topologically(spans_info)

                        for span_info in sorted_spans:
                            # Clean feedback scores to remove read-only fields
                            span_feedback_scores = clean_feedback_scores(
                                span_info.get("feedback_scores")
                            )

                            original_span_id = span_info.get("id")
                            # Translate parent_span_id to the new ID if it was already created
                            original_parent_span_id = span_info.get("parent_span_id")

                            new_parent_span_id = None
                            if (
                                original_parent_span_id
                                and original_parent_span_id in span_id_map
                            ):
                                new_parent_span_id = span_id_map[
                                    original_parent_span_id
                                ]

                            # Create span with parent_span_id if available
                            # Clean usage data to avoid double-prefixing of original_usage keys
                            usage_data = clean_usage_for_import(span_info.get("usage"))

                            span = client.span(
                                name=span_info.get("name", "imported_span"),
                                type=span_info.get("type", "general"),
                                start_time=(
                                    datetime.fromisoformat(
                                        span_info["start_time"].replace("Z", "+00:00")
                                    )
                                    if span_info.get("start_time")
                                    else None
                                ),
                                end_time=(
                                    datetime.fromisoformat(
                                        span_info["end_time"].replace("Z", "+00:00")
                                    )
                                    if span_info.get("end_time")
                                    else None
                                ),
                                input=span_info.get("input", {}),
                                output=span_info.get("output", {}),
                                metadata=build_import_metadata(
                                    span_info,
                                    _SPAN_IMPORT_FIELDS,
                                    span_info.get("metadata"),
                                ),
                                tags=span_info.get("tags"),
                                usage=usage_data,
                                feedback_scores=span_feedback_scores,
                                model=span_info.get("model"),
                                provider=span_info.get("provider"),
                                error_info=span_info.get("error_info"),
                                total_cost=span_info.get("total_cost"),
                                trace_id=trace.id,
                                parent_span_id=new_parent_span_id,
                                project_name=project_name,
                            )

                            # Map original span ID to new span ID for parent relationship mapping
                            if original_span_id and span.id:
                                span_id_map[original_span_id] = span.id

                        # Queue attachment uploads while span_id_map is still
                        # populated. Uploads run in the background via the
                        # streamer (retries + parallelism); flush() is called
                        # after all traces for this project are processed.
                        if include_attachments and not dry_run:
                            attachments = trace_data.get("attachments", [])
                            if attachments:
                                _upload_attachments_for_trace(
                                    client=client,
                                    project_dir=project_dir,
                                    attachments=attachments,
                                    new_trace_id=trace.id,
                                    span_id_map=span_id_map,
                                    project_name=project_name,
                                )

                        traces_imported += 1

                        # Mark file as completed and flush trace mapping to disk
                        if manifest:
                            manifest.mark_file_completed(trace_file)

                    except Exception as e:
                        console.print(
                            f"[red]Error importing trace from {trace_file}: {e}[/red]"
                        )
                        if manifest:
                            manifest.mark_file_failed(trace_file, str(e))
                        traces_errors += 1
                        continue

                # Flush queued attachment uploads (and any other streamed messages)
                # before proceeding so all data is persisted before we move on.
                client.flush()

                # Handle experiment recreation if requested
                if recreate_experiments_flag:
                    experiment_files = list(project_dir.glob("experiment_*.json"))
                    if experiment_files:
                        debug_print(
                            f"Found {len(experiment_files)} experiment files in project {project_name}",
                            debug,
                        )

                        experiments_recreated = recreate_experiments(
                            client,
                            project_dir,
                            project_name,
                            dry_run,
                            name_pattern,
                            trace_id_map,
                            None,  # dataset_item_id_map - not available in project import context
                            debug,
                            manifest=manifest,
                        )

                        if experiments_recreated > 0:
                            debug_print(
                                f"Recreated {experiments_recreated} experiments for project {project_name}",
                                debug,
                            )

                total_traces_imported += traces_imported
                total_traces_errors += traces_errors

                if traces_imported > 0:
                    imported_count += 1
                    debug_print(
                        f"Imported project: {project_name} with {traces_imported} traces",
                        debug,
                    )

            except Exception as e:
                console.print(
                    f"[red]Error importing project {project_dir.name}: {e}[/red]"
                )
                error_count += 1
                continue

        return {
            "projects": imported_count,
            "projects_skipped": skipped_count,
            "projects_errors": error_count,
            "traces": total_traces_imported,
            "traces_errors": total_traces_errors,
        }

    except Exception as e:
        console.print(f"[red]Error importing projects: {e}[/red]")
        return {
            "projects": 0,
            "projects_skipped": 0,
            "projects_errors": 1,
            "traces": 0,
            "traces_errors": 0,
        }
