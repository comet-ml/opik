"""Name resolution for ``opik migrate dataset``.

Entity-agnostic helpers (pagination, project-name lookup, collision
check, project preflight) live in ``cli/migrate/_resolver.py`` — Slice 6
(OPIK-6574) extracted them once the prompts mirror made the right shape
obvious. This module keeps only dataset-specific glue: the
``ResolvedDataset`` dataclass and the ``resolve_source`` /
``name_taken_in_workspace`` wrappers that bind the dataset Fern surface.

Dataset names are workspace-unique (BE liquibase migration
``000001_init_script.sql`` enforces ``UNIQUE (workspace_id, name)`` on
the ``datasets`` table, with no soft-delete and no INSERT-IGNORE
fallback in ``DatasetDAO.save``), so a workspace lookup either yields
one row or zero — no ambiguity disambiguation needed, no
``--from-project`` flag at the CLI surface.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, List, Optional

import opik

from .._resolver import (
    DEFAULT_PAGE_SIZE,
    ensure_destination_project_exists as _ensure_destination_project_exists,
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
    client: opik.Opik, *, name: Optional[str]
) -> Callable[[int, int], Any]:
    """Build a ``(page, size) -> response`` closure for ``find_datasets``.

    Stays on ``client.rest_client.datasets`` (the low-level Fern surface):
    the high-level ``client.get_datasets()`` wrapper returns SDK
    ``Dataset`` objects that drop ``project_id`` and
    ``dataset_items_count``, both of which the planner needs for
    collision detection and progress reporting.
    """
    rest_client = client.rest_client

    def _list(page: int, size: int) -> Any:
        return rest_client.datasets.find_datasets(page=page, size=size, name=name)

    return _list


def resolve_source(client: opik.Opik, name: str) -> ResolvedDataset:
    """Resolve ``name`` to a single dataset, or raise.

    Workspace uniqueness means a name lookup yields at most one row.
    Zero matches raise ``DatasetNotFoundError``; the (unreachable)
    >1-matches case raises ``ConflictError`` with an
    "invariant violated" message.

    The matched row's ``project_id`` is resolved to a name so downstream
    ``Opik.get_dataset`` / ``delete_dataset`` calls get the correct
    project context (workspace-scoped legacy datasets return None here,
    which the dataset helpers tolerate).
    """
    row = resolve_unique_source_by_name(
        client,
        list_fn=_datasets_list_fn(client, name=name),
        name=name,
        not_found_error=DatasetNotFoundError,
        invariant_violated_error=ConflictError,
        entity_label="dataset",
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

    Returns a human-readable label for the colliding project (or
    ``"another project"`` when workspace-scoped / project lookup fails),
    or ``None`` when the name is free. ``ignore_dataset_id`` excludes
    the source dataset itself from the check (it's about to be renamed).
    """
    return _name_taken_in_workspace(
        client,
        list_fn=_datasets_list_fn(client, name=name),
        name=name,
        ignore_id=ignore_dataset_id,
    )


# Re-exported so existing imports keep working (callers may have
# imported these symbols directly from this module historically).
__all__ = [
    "DEFAULT_PAGE_SIZE",
    "ResolvedDataset",
    "ensure_destination_project_exists",
    "name_taken_in_workspace",
    "resolve_source",
]
