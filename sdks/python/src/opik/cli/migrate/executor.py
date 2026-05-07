"""Executor for ``MigrationPlan`` actions.

Each action is wrapped in audit-log entries (``in_progress`` -> ``ok`` /
``failed``). ``DeleteSource`` only fires after every preceding action
succeeds; the planner already places it last, and any exception aborts the
loop before reaching it.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List

import opik
from opik.rest_api import OpikApi
from rich.console import Console

from .audit import AuditLog
from .planner import (
    CopyCurrentItems,
    CreateDestination,
    DeleteSource,
    MigrationPlan,
    RenameSource,
)

LOGGER = logging.getLogger(__name__)
_console = Console()


def execute_plan(
    client: opik.Opik,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Apply ``plan`` against ``client``, recording each action in ``audit``."""
    rest_client = client.rest_client
    for action in plan.actions:
        details = _action_details(action)
        audit.record(type=details["type"], status="in_progress", details=details)
        try:
            _apply(client, rest_client, action)
        except Exception as exc:
            audit.record(
                type=details["type"],
                status="failed",
                details=details,
                error=str(exc),
            )
            raise
        audit.record(type=details["type"], status="ok", details=details)


def record_planned(plan: MigrationPlan, audit: AuditLog) -> None:
    """Record every planned action with status=planned (used for dry-run)."""
    for action in plan.actions:
        details = _action_details(action)
        audit.record(type=details["type"], status="planned", details=details)


def _apply(client: opik.Opik, rest_client: OpikApi, action: object) -> None:
    if isinstance(action, RenameSource):
        rest_client.datasets.update_dataset(id=action.source_id, name=action.to_name)
    elif isinstance(action, CreateDestination):
        client.create_dataset(
            name=action.name,
            description=action.description,
            project_name=action.project_name,
        )
    elif isinstance(action, CopyCurrentItems):
        _copy_items(client, action)
    elif isinstance(action, DeleteSource):
        client.delete_dataset(
            name=action.name_after_rename, project_name=action.project_name
        )
    else:
        raise TypeError(f"Unknown migration action: {type(action).__name__}")


def _copy_items(client: opik.Opik, action: CopyCurrentItems) -> None:
    source = client.get_dataset(
        action.source_name_after_rename, project_name=action.source_project_name
    )
    items = source.get_items()
    if not items:
        return

    # get_items() streams newest-first; insert() preserves call order, and the
    # UI also defaults to newest-first. Without reversing, the target ends up
    # displayed in the opposite order from the source.
    items.reverse()

    items_to_insert: List[Dict[str, Any]] = [
        {k: v for k, v in item.items() if k != "id"} for item in items
    ]
    dest = client.get_dataset(action.dest_name, project_name=action.dest_project_name)

    # Single insert() call (not a chunked loop): Dataset.insert() is documented
    # to coalesce all internal HTTP batches under one batch_group_id, producing
    # exactly ONE dataset version on the target. A CLI-level chunked loop would
    # generate one version per chunk — wrong for slice 1's "current items only"
    # contract and would corrupt the starting state for slice 2's version
    # history replay. Trade-off: per-chunk progress count is replaced by a
    # spinner that conveys liveness without falsely implying ticks per batch.
    with _console.status(
        f"Copying {len(items_to_insert):,} items → {action.dest_name}…",
        spinner="dots",
    ):
        dest.insert(items_to_insert)


def _action_details(action: object) -> Dict[str, Any]:
    if isinstance(action, RenameSource):
        return {
            "type": "rename_source",
            "entity": "dataset",
            "id": action.source_id,
            "from": action.from_name,
            "to": action.to_name,
        }
    if isinstance(action, CreateDestination):
        return {
            "type": "create_destination",
            "entity": "dataset",
            "name": action.name,
            "project": action.project_name,
        }
    if isinstance(action, CopyCurrentItems):
        return {
            "type": "copy_dataset_items",
            "from_dataset": action.source_name_after_rename,
            "from_project": action.source_project_name,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
        }
    if isinstance(action, DeleteSource):
        return {
            "type": "delete_source",
            "entity": "dataset",
            "name": action.name_after_rename,
            "project": action.project_name,
        }
    raise TypeError(f"Unknown migration action: {type(action).__name__}")
