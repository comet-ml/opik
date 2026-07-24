"""Common utilities for import functionality."""

import hashlib
import json
import sys
from collections import deque
from pathlib import Path
from typing import Any, Callable, Dict, List, Optional, Tuple

import click
import opik
from opik.types import FeedbackScoreDict
from rich.console import Console
from rich.table import Table

from ..migration_manifest import MANIFEST_FILENAME, MigrationManifest

PROJECT_METADATA_FILENAME = "project.json"


def no_attachments_option() -> Callable:
    """Shared Click decorator for the ``--no-attachments`` flag."""
    return click.option(
        "--no-attachments",
        is_flag=True,
        help="Skip uploading attachment files.",
    )


def to_project_option() -> Callable:
    """Shared Click decorator for the ``--to-project`` flag.

    Overrides the destination project. When omitted, data is imported into a
    project named after the source (the name recorded in ``project.json``).
    """
    return click.option(
        "--to-project",
        "to_project",
        type=str,
        default=None,
        help="Create the imported data in this project instead of the source project's name.",
    )


def to_workspace_option() -> Callable:
    """Shared Click decorator for the ``--to-workspace`` flag.

    Overrides the destination workspace. When omitted, data is imported into
    the same workspace named on the command line (which is also used to locate
    the exported files on disk under ``<path>/WORKSPACE/projects/``).
    """
    return click.option(
        "--to-workspace",
        "to_workspace",
        type=str,
        default=None,
        help=(
            "Import data into this workspace instead of WORKSPACE. "
            "WORKSPACE is still used to locate the exported files on disk."
        ),
    )


console = Console()


def resolve_import_base_path(path: str, workspace: str) -> Path:
    """Resolve the directory that contains ``projects/`` for import.

    Prefers ``<path>/<workspace>/`` — symmetric with the export layout
    (``<path>/<workspace>/projects/<id>/``), so the same ``--path`` round-trips
    within a workspace. Falls back to ``<path>/`` when the workspace segment is
    absent, which is the cross-workspace case: when importing into a different
    workspace than the data was exported from, point ``--path`` at the exported
    workspace directory and the project folders are found directly under it.
    """
    workspace_scoped = Path(path) / workspace
    if (workspace_scoped / "projects").is_dir():
        return workspace_scoped
    return Path(path)


def _read_project_metadata_name(project_dir: Path) -> Optional[str]:
    """Return the recorded project name from ``<project_dir>/project.json``.

    Returns ``None`` for a missing/unreadable/malformed file, a non-object JSON
    body, or a non-string ``name`` — so a corrupt ``project.json`` can never
    crash ``find_project_export_dir`` / ``available_project_names``.
    """
    meta = project_dir / PROJECT_METADATA_FILENAME
    if not meta.exists():
        return None
    try:
        data = json.loads(meta.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(data, dict):
        return None
    name = data.get("name")
    return name if isinstance(name, str) else None


def find_project_export_dir(base_path: Path, project_name: str) -> Optional[Path]:
    """Locate the exported project folder whose ``project.json`` name matches.

    The on-disk layout keys folders by project ID; the human name lives in
    ``project.json``. This resolves a user-supplied project *name* back to its
    id-named directory under ``base_path/projects/``. Returns ``None`` if no
    folder records that name.
    """
    projects_dir = base_path / "projects"
    if not projects_dir.is_dir():
        return None
    matches = [
        child
        for child in sorted(projects_dir.iterdir())
        if child.is_dir() and _read_project_metadata_name(child) == project_name
    ]
    if not matches:
        return None
    if len(matches) > 1:
        # Ambiguous: e.g. a project renamed and re-exported, or the name reused
        # across folders. Surface it rather than silently picking by sort order.
        console.print(
            f"[yellow]Warning: {len(matches)} exported folders record project "
            f"name '{project_name}' ({', '.join(m.name for m in matches)}); "
            f"using '{matches[0].name}'.[/yellow]"
        )
    return matches[0]


def available_project_names(base_path: Path) -> List[str]:
    """List the project names recorded under ``base_path/projects/*/project.json``."""
    projects_dir = base_path / "projects"
    if not projects_dir.is_dir():
        return []
    names = [
        name
        for child in sorted(projects_dir.iterdir())
        if child.is_dir() and (name := _read_project_metadata_name(child)) is not None
    ]
    return names


def resolve_import_project_root(
    path: str, workspace: str, project_name: str, to_project: Optional[str] = None
) -> Tuple[Path, str]:
    """Locate the exported project folder and resolve the destination project.

    Shared by ``import_all`` and ``_import_by_type`` so the source lookup and the
    not-found messaging stay in sync. Returns ``(project_root, target_project_name)``
    where ``target_project_name`` is ``to_project`` when given, else ``project_name``.
    Prints an error and exits with code 1 when no exported project records the name.
    """
    base_path = resolve_import_base_path(path, workspace)
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
    return project_root, (to_project or project_name)


def destination_manifest_dir(
    project_root: Path,
    destination_project_name: str,
    destination_workspace: Optional[str] = None,
) -> Path:
    """Return the per-destination import-manifest directory under the source folder.

    The resume/completion manifest must be keyed by both the *destination* workspace
    and project, not just the source folder. Importing the same exported project into
    different ``--to-project`` or ``--to-workspace`` targets must keep independent
    state (each destination gets its own newly-created trace ids and completion
    status). Without this, a clean import into workspace A / project X would make a
    later import into workspace B / project X short-circuit on
    ``manifest.is_completed``.

    Destination names may contain ``/``, ``:`` or whitespace, so the directory is
    keyed by a short hash of the combined destination rather than the names themselves.
    """
    dest_str = f"{destination_workspace or ''}:{destination_project_name}"
    dest_key = hashlib.sha256(dest_str.encode("utf-8")).hexdigest()[:16]
    return project_root / "import_manifests" / dest_key


def setup_import_manifest(
    project_root: Path,
    destination_project_name: str,
    dry_run: bool,
    force: bool,
    destination_workspace: Optional[str] = None,
) -> Tuple[Optional[MigrationManifest], bool]:
    """Construct and initialize the per-destination import manifest.

    Shared by ``import_all`` and ``_import_by_type`` so the start/resume/force
    lifecycle stays in one place. The manifest's ``base_path`` is ``project_root``
    (so file keys under ``project_root/datasets`` etc. resolve), while the SQLite
    file lives in the per-destination subdir from :func:`destination_manifest_dir`.

    Returns ``(manifest, already_completed)``. ``manifest`` is ``None`` for dry
    runs. When ``already_completed`` is ``True`` (a prior run finished and
    ``--force`` was not given), the caller should return without re-importing.
    """
    if dry_run:
        return None, False

    manifest_file = (
        destination_manifest_dir(
            project_root, destination_project_name, destination_workspace
        )
        / MANIFEST_FILENAME
    )
    # Capture existence BEFORE constructing — the constructor creates the SQLite
    # file, which would otherwise make the --force "existing manifest" check
    # tautologically true (and reset/warn against a manifest that never existed)
    # on a first run.
    manifest_existed = MigrationManifest.exists(
        project_root, manifest_path=manifest_file
    )
    manifest = MigrationManifest(project_root, manifest_path=manifest_file)
    if force:
        if manifest_existed:
            manifest.reset()
            console.print(
                "[yellow]--force: discarding existing manifest, starting fresh[/yellow]"
            )
    else:
        if manifest.is_completed:
            console.print(
                "[green]Import already completed. Use --force to re-import.[/green]"
            )
            return manifest, True
        elif manifest.is_in_progress:
            console.print(
                f"[blue]Resuming interrupted import: "
                f"{manifest.completed_count()} file(s) already completed[/blue]"
            )
    manifest.start()
    return manifest, False


def finalize_import(
    manifest: Optional[MigrationManifest],
    client: opik.Opik,
    total_errors: int,
    dry_run: bool,
) -> None:
    """Flush ingestion, surface upload failures, and complete the manifest.

    Shared tail of ``import_all`` and ``_import_by_type`` so resume/manifest
    tweaks stay in one place. No-op on dry runs. Exits with code 1 if the flush
    times out or any file upload failed. Marks the manifest complete only when
    ``total_errors == 0``, so a partial failure leaves it ``in_progress`` for a
    resumable rerun.
    """
    if dry_run:
        return

    # Flush the async ingestion queue so all enqueued traces/spans are persisted
    # before we return; otherwise the process can exit while the background
    # worker is still sending data (especially under rate-limiting).
    flushed = client.flush()
    if not flushed:
        console.print(
            "[yellow]Warning: flush timed out — some traces/spans may not have been "
            "ingested. Re-run the import to retry.[/yellow]"
        )
        sys.exit(1)

    # FileUploadManager.flush() returns True even when individual uploads fail
    # (only False on timeout), so check failed_uploads separately.
    failed = client.__internal_api__failed_uploads__(timeout=None)
    if failed > 0:
        console.print(
            f"[yellow]Warning: {failed} file upload(s) failed during import. "
            "Re-run the import to retry.[/yellow]"
        )
        sys.exit(1)

    # Mark complete only on a fully clean run. On any error, leave it in_progress
    # so the next run (without --force) resumes/retries instead of
    # short-circuiting on manifest.is_completed.
    assert manifest is not None  # guaranteed: manifest is set whenever not dry_run
    if total_errors == 0:
        manifest.complete()


def debug_print(message: str, debug: bool) -> None:
    """Print debug message only if debug is enabled."""
    if debug:
        console.print(f"[blue]{message}[/blue]")


def matches_name_pattern(name: str, pattern: Optional[str]) -> bool:
    """Check if a name matches the given pattern using case-insensitive substring matching."""
    if pattern is None:
        return True
    # Simple string matching - check if pattern is contained in name (case-insensitive)
    return pattern.lower() in name.lower()


def translate_trace_id(
    original_trace_id: str, trace_id_map: Dict[str, str]
) -> Optional[str]:
    """Translate an original trace id from export to the newly created id.

    Args:
        original_trace_id: The original trace ID from the export
        trace_id_map: Mapping from original trace IDs to new trace IDs (required, can be empty)

    Returns:
        The new trace ID if found in the map, None otherwise.
    """
    return trace_id_map.get(original_trace_id)


def find_dataset_item_by_content(
    dataset: opik.Dataset, expected_content: Dict[str, Any]
) -> Optional[str]:
    """Find a dataset item by matching its content.

    Compares all fields in expected_content (excluding 'id') with dataset items.
    """
    try:
        items = dataset.get_items()
        # Remove 'id' from expected_content for comparison
        expected_without_id = {k: v for k, v in expected_content.items() if k != "id"}

        for item in items:
            # Compare all fields (excluding 'id')
            item_without_id = {k: v for k, v in item.items() if k != "id"}
            if item_without_id == expected_without_id:
                return item.get("id")
    except Exception:
        pass
    return None


def create_dataset_item(dataset: opik.Dataset, item_data: Dict[str, Any]) -> str:
    """Create a dataset item and return its ID.

    Uses all fields from item_data (except 'id') to support flexible dataset schemas.
    """
    # Use all fields from item_data except 'id' (which is handled separately)
    new_item = {k: v for k, v in item_data.items() if k != "id" and v is not None}

    # Ensure item is not empty (backend requires non-empty data)
    if not new_item:
        raise ValueError(
            "Dataset item data is empty - at least one field must be provided"
        )

    dataset.insert([new_item])

    # Find the newly created item by matching all fields
    items = dataset.get_items()
    for item in items:
        # Compare all fields (excluding 'id')
        item_without_id = {k: v for k, v in item.items() if k != "id"}
        if item_without_id == new_item:
            item_id = item.get("id")
            if item_id is not None:
                return item_id

    dataset_name = getattr(dataset, "name", None)
    dataset_info = f", Dataset: {dataset_name!r}" if dataset_name else ""
    raise Exception(
        f"Failed to create dataset item. Content: {new_item!r}{dataset_info}"
    )


def handle_trace_reference(item_data: Dict[str, Any]) -> Optional[str]:
    """Handle trace references from deduplicated exports."""
    trace_reference = item_data.get("trace_reference")
    if trace_reference:
        trace_id = trace_reference.get("trace_id")
        return trace_id

    # Fall back to direct trace_id
    return item_data.get("trace_id")


def clean_usage_for_import(
    usage: Optional[Dict[str, Any]],
) -> Optional[Dict[str, Any]]:
    """Return usage data as-is for import.

    When exporting, usage data is already in backend-compatible format with:
    - Top-level OpenAI-formatted keys: completion_tokens, prompt_tokens, total_tokens
    - Provider-specific keys with 'original_usage.' prefix

    The SDK's validation_helpers.validate_and_parse_usage now detects when usage
    data contains 'original_usage.' prefixed keys and passes it through unchanged,
    preserving the original values.

    Args:
        usage: The usage dictionary from exported data, or None

    Returns:
        The usage dictionary as-is (already in backend format), or None if input was None
    """
    # Return usage as-is - the SDK will detect the 'original_usage.' prefix
    # and pass it through without reprocessing
    return usage


def clean_feedback_scores(
    feedback_scores: Optional[List[Dict[str, Any]]],
) -> Optional[List[FeedbackScoreDict]]:
    """Clean feedback scores by removing fields that are not allowed when creating them.

    Exported feedback scores include read-only fields like 'source', 'created_at', etc.
    that must be removed before importing.

    Allowed fields: name, value, category_name, reason
    """
    if not feedback_scores:
        return None

    cleaned_scores: List[FeedbackScoreDict] = []
    for score in feedback_scores:
        if not isinstance(score, dict):
            continue

        # Only keep allowed fields
        name = score.get("name")
        value = score.get("value")

        # Only add if name and value are present
        if not name or value is None:
            continue

        # Construct FeedbackScoreDict with required fields
        cleaned_score: FeedbackScoreDict = {
            "name": name,
            "value": value,
        }

        # Add optional fields if they exist
        if "category_name" in score:
            cleaned_score["category_name"] = score.get("category_name")
        if "reason" in score:
            cleaned_score["reason"] = score.get("reason")

        cleaned_scores.append(cleaned_score)

    return cleaned_scores if cleaned_scores else None


# TODO(2026-05-10): Remove _import_* metadata fallback once the backend has been
# updated to accept these fields directly on the write path. At that point:
#   1. Pass the fields directly to client.trace(), client.span(), and
#      client.create_experiment() instead of (or in addition to) storing them
#      in metadata.
#   2. Remove the build_import_metadata() calls in project.py and experiment.py.
#   3. Remove build_import_metadata(), _TRACE_IMPORT_FIELDS, _SPAN_IMPORT_FIELDS,
#      and _EXPERIMENT_IMPORT_FIELDS from this file.
#
# Background: these fields are read-only on the current backend (created_by /
# created_at are hardcoded to the authenticated user / now() in TraceDAO and
# SpanDAO). They are stored in metadata so the information survives import
# against an old backend. New backends that accept the fields directly will
# ignore unknown body fields (JsonIgnoreProperties + JsonView exclusion), so
# the SDK can send both simultaneously during a transition period.
#
# Fields from exported traces/spans/experiments that cannot yet be set directly
# via the backend API. These are preserved in metadata under _import_* keys so
# the information is not lost, and can be migrated to the actual fields once the
# backend is updated to accept them.
_TRACE_IMPORT_FIELDS = [
    "created_at",
    "created_by",
    "last_updated_at",
    "last_updated_by",
    "visibility_mode",
    "ttft",
]

_SPAN_IMPORT_FIELDS = [
    "created_at",
    "created_by",
    "last_updated_at",
    "last_updated_by",
    "ttft",
]

_EXPERIMENT_IMPORT_FIELDS = [
    "created_at",
    "created_by",
    "last_updated_at",
    "last_updated_by",
]


def build_import_metadata(
    source: Dict[str, Any],
    fields: List[str],
    existing_metadata: Optional[Dict[str, Any]] = None,
) -> Optional[Dict[str, Any]]:
    """Return a metadata dict that includes import-preserved fields under _import_* keys.

    Only fields with non-None values are added. If there is nothing to add and
    existing_metadata is None, returns None so callers that had no metadata
    continue to send no metadata.
    """
    import_fields = {
        f"_import_{field}": source[field]
        for field in fields
        if source.get(field) is not None
    }
    if not import_fields:
        return existing_metadata
    merged: Dict[str, Any] = dict(existing_metadata) if existing_metadata else {}
    merged.update(import_fields)
    return merged


def sort_spans_topologically(spans_info: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
    """Sort spans in topological order to ensure parents are processed before children.

    This function performs a level-order traversal (breadth-first) of the span tree,
    ensuring that all parent spans are processed before their children, regardless
    of how deep the hierarchy goes.

    Args:
        spans_info: List of span dictionaries, each with 'id' and optional 'parent_span_id'

    Returns:
        List of spans sorted in topological order (parents before children)
    """
    if not spans_info:
        return []

    # Build children map: parent_id -> list of child spans
    # Also track all span IDs to detect missing parent references
    children_map: Dict[str, List[Dict[str, Any]]] = {}
    root_spans: List[Dict[str, Any]] = []
    all_span_ids = {span.get("id") for span in spans_info if span.get("id")}

    for span in spans_info:
        parent_span_id = span.get("parent_span_id")

        if not parent_span_id:
            # Root span (no parent)
            root_spans.append(span)
        elif parent_span_id in all_span_ids:
            # Child span with valid parent reference - add to children map
            if parent_span_id not in children_map:
                children_map[parent_span_id] = []
            children_map[parent_span_id].append(span)
        else:
            # Span references a parent that doesn't exist - treat as root span
            # This handles corrupted data gracefully
            root_spans.append(span)

    # Perform level-order traversal (breadth-first)
    result: List[Dict[str, Any]] = []
    visited: set[str] = set()

    # If no root spans exist (e.g., cycles), seed queue with all spans
    # This ensures we process all spans even in cyclic or disconnected graphs
    queue: deque[Dict[str, Any]]
    if not root_spans:
        queue = deque(spans_info)
    else:
        queue = deque(root_spans)

    while queue:
        current = queue.popleft()
        current_id = current.get("id")

        # Skip if already visited (prevents infinite loops in cycles)
        if current_id and current_id in visited:
            continue

        result.append(current)
        if current_id:
            visited.add(current_id)

        # Add all children of current span to queue (only if not visited)
        if current_id and current_id in children_map:
            for child in children_map[current_id]:
                child_id = child.get("id")
                if child_id and child_id not in visited:
                    queue.append(child)

    # Append any spans not yet visited (handles disconnected components)
    for span in spans_info:
        span_id = span.get("id")
        if span_id and span_id not in visited:
            result.append(span)

    return result


def print_import_summary(stats: Dict[str, int]) -> None:
    """Print a nice summary table of import statistics."""
    table = Table(
        title="📥 Import Summary", show_header=True, header_style="bold magenta"
    )
    table.add_column("Type", style="cyan", no_wrap=True)
    table.add_column("Imported", justify="right", style="green")
    table.add_column("Skipped", justify="right", style="yellow")
    table.add_column("Errors", justify="right", style="red")

    # Add rows for each type
    if (
        stats.get("experiments", 0) > 0
        or stats.get("experiments_skipped", 0) > 0
        or stats.get("experiments_errors", 0) > 0
    ):
        imported = stats.get("experiments", 0)
        skipped = stats.get("experiments_skipped", 0)
        errors = stats.get("experiments_errors", 0)
        table.add_row(
            "🧪 Experiments",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("datasets", 0) > 0
        or stats.get("datasets_skipped", 0) > 0
        or stats.get("datasets_errors", 0) > 0
    ):
        imported = stats.get("datasets", 0)
        skipped = stats.get("datasets_skipped", 0)
        errors = stats.get("datasets_errors", 0)
        table.add_row(
            "📊 Datasets",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("traces", 0) > 0
        or stats.get("traces_skipped", 0) > 0
        or stats.get("traces_errors", 0) > 0
    ):
        imported = stats.get("traces", 0)
        skipped = stats.get("traces_skipped", 0)
        errors = stats.get("traces_errors", 0)
        table.add_row(
            "🔍 Traces",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("prompts", 0) > 0
        or stats.get("prompts_skipped", 0) > 0
        or stats.get("prompts_errors", 0) > 0
    ):
        imported = stats.get("prompts", 0)
        skipped = stats.get("prompts_skipped", 0)
        errors = stats.get("prompts_errors", 0)
        table.add_row(
            "💬 Prompts",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    if (
        stats.get("projects", 0) > 0
        or stats.get("projects_skipped", 0) > 0
        or stats.get("projects_errors", 0) > 0
    ):
        imported = stats.get("projects", 0)
        skipped = stats.get("projects_skipped", 0)
        errors = stats.get("projects_errors", 0)
        table.add_row(
            "📁 Projects",
            str(imported),
            str(skipped) if skipped > 0 else "",
            str(errors) if errors > 0 else "",
        )

    # Calculate totals
    total_imported = sum(
        [
            stats.get(key, 0)
            for key in ["experiments", "datasets", "traces", "prompts", "projects"]
        ]
    )
    total_skipped = sum(
        [
            stats.get(key, 0)
            for key in [
                "experiments_skipped",
                "datasets_skipped",
                "traces_skipped",
                "prompts_skipped",
                "projects_skipped",
            ]
        ]
    )
    total_errors = sum(
        [
            stats.get(key, 0)
            for key in [
                "experiments_errors",
                "datasets_errors",
                "traces_errors",
                "prompts_errors",
                "projects_errors",
            ]
        ]
    )

    table.add_row("", "", "", "", style="bold")
    table.add_row(
        "📦 Total",
        str(total_imported),
        str(total_skipped) if total_skipped > 0 else "",
        str(total_errors) if total_errors > 0 else "",
        style="bold green",
    )

    console.print()
    console.print(table)
    console.print()
