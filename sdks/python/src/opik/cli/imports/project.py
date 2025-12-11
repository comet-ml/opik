"""Project import functionality."""

import json
from datetime import datetime
from pathlib import Path
from typing import Dict, Optional

import opik
from rich.console import Console

from .experiment import recreate_experiments
from .utils import matches_name_pattern, clean_feedback_scores

console = Console()


def import_projects_from_directory(
    client: opik.Opik,
    source_dir: Path,
    dry_run: bool,
    name_pattern: Optional[str],
    debug: bool,
    recreate_experiments_flag: bool = False,
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
        for project_dir in project_dirs:
            try:
                project_name = project_dir.name
                # Maintain a per-project mapping from original -> new trace ids
                trace_id_map: Dict[str, str] = {}

                # Filter by name pattern if specified
                if name_pattern and not matches_name_pattern(
                    project_name, name_pattern
                ):
                    if debug:
                        console.print(
                            f"[blue]Skipping project {project_name} (doesn't match pattern)[/blue]"
                        )
                    skipped_count += 1
                    continue

                if dry_run:
                    console.print(f"[blue]Would import project: {project_name}[/blue]")
                    imported_count += 1
                    continue

                if debug:
                    console.print(f"[blue]Importing project: {project_name}[/blue]")

                # Import traces from the project directory
                trace_files = list(project_dir.glob("trace_*.json"))
                traces_imported = 0
                traces_errors = 0

                for trace_file in trace_files:
                    try:
                        with open(trace_file, "r", encoding="utf-8") as f:
                            trace_data = json.load(f)

                        # Import trace and spans
                        trace_info = trace_data.get("trace", {})
                        spans_info = trace_data.get("spans", [])
                        original_trace_id = trace_info.get("id")

                        # Create trace with full data
                        # Clean feedback scores to remove read-only fields
                        feedback_scores = clean_feedback_scores(
                            trace_info.get("feedback_scores")
                        )

                        trace = client.trace(
                            name=trace_info.get("name", "imported_trace"),
                            start_time=(
                                datetime.fromisoformat(
                                    trace_info["start_time"].replace("Z", "+00:00")
                                )
                                if trace_info.get("start_time")
                                else None
                            ),
                            end_time=(
                                datetime.fromisoformat(
                                    trace_info["end_time"].replace("Z", "+00:00")
                                )
                                if trace_info.get("end_time")
                                else None
                            ),
                            input=trace_info.get("input", {}),
                            output=trace_info.get("output", {}),
                            metadata=trace_info.get("metadata"),
                            tags=trace_info.get("tags"),
                            feedback_scores=feedback_scores,
                            error_info=trace_info.get("error_info"),
                            thread_id=trace_info.get("thread_id"),
                            project_name=project_name,
                        )

                        if original_trace_id:
                            trace_id_map[original_trace_id] = trace.id

                        # Create spans with full data, preserving parent-child relationships
                        # Build span_id_map to translate parent_span_id references
                        span_id_map: Dict[
                            str, str
                        ] = {}  # Maps original span ID to new span ID

                        # Sort spans to process root spans (no parent) first, then children
                        root_spans = [
                            s for s in spans_info if not s.get("parent_span_id")
                        ]
                        child_spans = [s for s in spans_info if s.get("parent_span_id")]
                        sorted_spans = root_spans + child_spans

                        for span_info in sorted_spans:
                            # Clean feedback scores to remove read-only fields
                            span_feedback_scores = clean_feedback_scores(
                                span_info.get("feedback_scores")
                            )

                            original_span_id = span_info.get("id")
                            original_parent_span_id = span_info.get("parent_span_id")

                            # Translate parent_span_id if it exists
                            new_parent_span_id = None
                            if (
                                original_parent_span_id
                                and original_parent_span_id in span_id_map
                            ):
                                new_parent_span_id = span_id_map[
                                    original_parent_span_id
                                ]

                            # Create span with parent_span_id if available
                            span = client.span(
                                name=span_info.get("name", "imported_span"),
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
                                metadata=span_info.get("metadata"),
                                tags=span_info.get("tags"),
                                usage=span_info.get("usage"),
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

                        traces_imported += 1

                    except Exception as e:
                        console.print(
                            f"[red]Error importing trace from {trace_file}: {e}[/red]"
                        )
                        traces_errors += 1
                        continue

                # Handle experiment recreation if requested
                if recreate_experiments_flag:
                    # Flush client before recreating experiments
                    client.flush()

                    experiment_files = list(project_dir.glob("experiment_*.json"))
                    if experiment_files:
                        if debug:
                            console.print(
                                f"[blue]Found {len(experiment_files)} experiment files in project {project_name}[/blue]"
                            )

                        # Recreate experiments
                        experiments_recreated = recreate_experiments(
                            client,
                            project_dir,
                            project_name,
                            dry_run,
                            name_pattern,
                            trace_id_map,
                            None,  # dataset_item_id_map - not available in project import context
                            debug,
                        )

                        if debug and experiments_recreated > 0:
                            console.print(
                                f"[green]Recreated {experiments_recreated} experiments for project {project_name}[/green]"
                            )

                total_traces_imported += traces_imported
                total_traces_errors += traces_errors

                if traces_imported > 0:
                    imported_count += 1
                    if debug:
                        console.print(
                            f"[green]Imported project: {project_name} with {traces_imported} traces[/green]"
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
