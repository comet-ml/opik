"""Name resolution for ``opik migrate dataset``.

Entity-agnostic helpers (pagination, project-name lookup, collision
check, project preflight) live in ``cli/migrate/_resolver.py``. This
module keeps only dataset-specific glue: the ``ResolvedDataset``
dataclass and the ``resolve_source`` / ``name_taken_in_workspace``
wrappers that bind the dataset Fern surface.

Dataset names are workspace-unique (BE liquibase migration
``000001_init_script.sql`` enforces ``UNIQUE (workspace_id, name)`` on
the ``datasets`` table), so a name lookup yields at most one row.
``--from-project`` is still useful as an **optional** filter:

* Smaller result set from the BE on workspaces with many entities.
* Sharper not-found message: "not found in project 'A'" vs "not found
  in the workspace" — better debugging signal when the user has a
  mental model of the source project.
* Targets a V1/auto-migrated workspace-scoped row vs an explicitly
  project-scoped row when the user wants to assert which one to copy.

Omit ``--from-project`` to target workspace-scoped entities (V1
entities, or anything left at workspace scope after auto-migration).
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, List, Optional

import opik
from opik.api_objects import rest_helpers

from .._resolver import (
    DEFAULT_PAGE_SIZE,
    ensure_destination_project_exists as _ensure_destination_project_exists,
    find_row_in_workspace as _find_row_in_workspace,
    make_list_fn,
    name_taken_in_workspace as _name_taken_in_workspace,
    project_name_for_row,
    resolve_unique_source_by_name,
)
from ..errors import ConflictError, DatasetNotFoundError


@dataclass(frozen=True)
class ResolvedDataset:
    id: str
    name: str
    project_name: Optional[str]
    description: Optional[str]
    # Type ('dataset' or 'evaluation_suite' for test suites). The planner
    # forwards this into the plan so the target inherits the same type;
    # for suites the executor's CopyTestSuiteConfig action additionally
    # copies the latest version's evaluators + execution_policy.
    type: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]


def _datasets_list_fn(
    client: opik.Opik, *, name: Optional[str], project_id: Optional[str]
) -> Callable[[int, int], Any]:
    """Build the dataset ``(page, size) -> response`` closure.

    Stays on the low-level Fern surface
    (``client.rest_client.datasets.find_datasets``): the high-level
    ``client.get_datasets()`` wrapper returns SDK ``Dataset`` objects
    that drop ``project_id`` and ``dataset_items_count``, both of which
    the planner needs.

    ``project_id`` filters the BE query when provided (smaller result
    set on workspaces with many entities); ``None`` is workspace-wide.
    """
    return make_list_fn(
        client.rest_client.datasets.find_datasets,
        name=name,
        project_id=project_id,
    )


def resolve_source(
    client: opik.Opik,
    name: str,
    from_project: Optional[str] = None,
) -> ResolvedDataset:
    """Resolve ``name`` (optionally scoped to ``from_project``) to one dataset.

    ``from_project=None`` does a workspace-wide lookup. When provided,
    the BE filters by the resolved ``project_id`` so the result set is
    smaller and the not-found error message names the project. Workspace
    name uniqueness means a workspace lookup also yields at most one
    row; the flag is primarily a perf / UX hint when the user knows the
    source project.

    Raises ``DatasetNotFoundError`` on zero matches. The (architecturally
    unreachable) >1-matches branch raises ``ConflictError`` with an
    "invariant violated" message — defensive against a BE constraint
    bypass.
    """
    project_id = rest_helpers.resolve_project_id_by_name_optional(
        client.rest_client, project_name=from_project
    )
    scope_label = f"project '{from_project}'" if from_project else "the workspace"
    row = resolve_unique_source_by_name(
        client,
        list_fn=_datasets_list_fn(client, name=name, project_id=project_id),
        name=name,
        not_found_error=DatasetNotFoundError,
        invariant_violated_error=ConflictError,
        entity_label="dataset",
        scope_label=scope_label,
    )
    return ResolvedDataset(
        id=row.id,
        name=row.name,
        project_name=project_name_for_row(client, row),
        description=row.description,
        type=getattr(row, "type", None),
        visibility=getattr(row, "visibility", None),
        tags=getattr(row, "tags", None),
    )


def ensure_destination_project_exists(client: opik.Opik, to_project: str) -> str:
    """Resolve and return ``to_project`` ID, or raise ``ProjectNotFoundError``."""
    return _ensure_destination_project_exists(client, to_project)


def name_taken_in_workspace(
    client: opik.Opik,
    name: str,
    *,
    ignore_dataset_id: Optional[str] = None,
) -> Optional[str]:
    """Workspace-wide name collision check for the rename PUT pre-flight.

    Always scopes workspace-wide (``project_id=None``): the rename
    target must be free across the entire workspace because
    ``(workspace_id, name)`` is the BE's unique key. ``ignore_dataset_id``
    excludes the source dataset itself from the check (it's about to be
    renamed). Returns a human-readable label for the colliding project,
    or ``None`` when the name is free.
    """
    return _name_taken_in_workspace(
        client,
        list_fn=_datasets_list_fn(client, name=name, project_id=None),
        name=name,
        ignore_id=ignore_dataset_id,
    )


def find_dataset_in_workspace(
    client: opik.Opik,
    name: str,
    *,
    ignore_dataset_id: Optional[str] = None,
) -> Optional[Any]:
    """Return the workspace dataset row named ``name``, or ``None``.

    Used by the planner to detect a stale temp destination
    (``<source>__migrating``) left by a prior failed run so it can be
    discarded before the re-run recreates it. Returns the raw Fern row
    (``.id`` / ``.name``); workspace name uniqueness means at most one row
    matches. Always scopes workspace-wide (``project_id=None``).
    """
    return _find_row_in_workspace(
        list_fn=_datasets_list_fn(client, name=name, project_id=None),
        name=name,
        ignore_id=ignore_dataset_id,
    )


# Re-exported so existing imports keep working (callers may have
# imported these symbols directly from this module historically).
__all__ = [
    "DEFAULT_PAGE_SIZE",
    "ResolvedDataset",
    "ensure_destination_project_exists",
    "find_dataset_in_workspace",
    "name_taken_in_workspace",
    "resolve_source",
]
