"""Name resolution for ``opik migrate``.

Dataset names are workspace-unique (BE liquibase migration
``000001_init_script.sql`` enforces ``UNIQUE (workspace_id, name)`` on
the ``datasets`` table, with no soft-delete carve-out), so a workspace
lookup either yields one row or zero — no ambiguity disambiguation
needed, no ``--from-project`` flag at the CLI surface.
"""

from __future__ import annotations

import difflib
import logging
from dataclasses import dataclass
from typing import Any, Iterable, List, Optional

import opik
from opik.api_objects import rest_helpers
from opik.rest_api.core.api_error import ApiError

from ..errors import ConflictError, DatasetNotFoundError, ProjectNotFoundError

LOGGER = logging.getLogger(__name__)

# Cap the candidate list we feed to difflib. Workspaces with >100 projects
# are uncommon, and the suggestion quality plateaus quickly past that.
_PROJECT_SUGGESTION_PAGE_SIZE = 100

# Page size used when paginating dataset lookups. Larger pages = fewer
# round-trips on workspaces with many same-named datasets.
_FIND_DATASETS_PAGE_SIZE = 100


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


def _iter_dataset_pages(
    client: opik.Opik,
    *,
    name: Optional[str] = None,
) -> Iterable[Any]:
    """Yield every dataset row matching the name filter, paginating to exhaustion.

    Single source of truth for ``find_datasets`` pagination inside this
    module. Avoids the silent truncation bug where a single
    ``find_datasets(page=1)`` call would miss rows on later pages.

    Stays on ``client.rest_client`` (the low-level Fern surface): the
    high-level ``client.get_datasets()`` wrapper returns SDK ``Dataset``
    objects that drop the ``project_id`` and ``dataset_items_count`` fields
    the planner needs for collision detection and progress reporting.
    """
    rest_client = client.rest_client
    page_idx = 1
    while True:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda p=page_idx: rest_client.datasets.find_datasets(
                page=p,
                size=_FIND_DATASETS_PAGE_SIZE,
                name=name,
            )
        )
        if not page.content:
            return
        for row in page.content:
            yield row
        if len(page.content) < _FIND_DATASETS_PAGE_SIZE:
            return
        page_idx += 1


def _project_name_for_row(client: opik.Opik, row: Any) -> Optional[str]:
    """Resolve a dataset row's ``project_id`` to a human-readable name.

    Returns None when the dataset has no project (workspace-scoped) or when
    the project lookup raises a recoverable ``ApiError`` (typically 404 — the
    project was deleted out from under us between listing and lookup). The
    fallback is intentional: a missing project label is fine to omit from a
    collision message, and downstream code accepts ``None`` as
    "workspace-scoped".

    Unexpected exceptions (auth, transport, config) are logged at WARNING and
    we still return ``None`` so the surrounding check doesn't fail mid-flight,
    but the cause is surfaced rather than silently swallowed.
    """
    project_id = getattr(row, "project_id", None)
    if not project_id:
        return None
    try:
        proj = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: client.get_project(id=project_id)
        )
        return getattr(proj, "name", None)
    except ApiError:
        # Recoverable: 404 (project gone) or similar; row.id is still usable.
        return None
    except Exception as exc:
        LOGGER.warning(
            "Unexpected error resolving project id %s to a name: %s",
            project_id,
            exc.__class__.__name__,
        )
        return None


def resolve_source(client: opik.Opik, name: str) -> ResolvedDataset:
    """Resolve ``name`` to a single dataset.

    Dataset names are workspace-unique (BE constraint), so we walk every
    ``find_datasets`` page filtering by name and expect at most one
    match. Zero matches raise ``DatasetNotFoundError``.

    The matched row's ``project_id`` is resolved to a name so downstream
    ``Opik.get_dataset`` / ``delete_dataset`` calls get the correct
    project context (workspace-scoped legacy datasets return None here,
    which the dataset helpers tolerate).
    """
    matches: List[ResolvedDataset] = []
    for row in _iter_dataset_pages(client, name=name):
        if row.name != name:
            continue
        matches.append(
            ResolvedDataset(
                id=row.id,
                name=row.name,
                project_name=_project_name_for_row(client, row),
                description=row.description,
                type=getattr(row, "type", None),
                visibility=getattr(row, "visibility", None),
                tags=getattr(row, "tags", None),
            )
        )

    if not matches:
        raise DatasetNotFoundError(f"Dataset '{name}' not found in the workspace.")

    # Workspace uniqueness means >1 matches would imply a BE invariant
    # violation; surface it explicitly rather than silently picking one.
    if len(matches) > 1:
        raise ConflictError(
            f"Dataset name '{name}' matched {len(matches)} rows — the workspace "
            "uniqueness invariant appears to be violated. Aborting; investigate "
            "via the UI before retrying."
        )

    return matches[0]


def ensure_destination_project_exists(
    client: opik.Opik,
    to_project: str,
) -> str:
    """Resolve and return the ``to_project`` ID, or raise ``ProjectNotFoundError``.

    Slice 1 fails fast rather than auto-creating a project: a typo in
    ``--to-project`` would otherwise silently strand the migration under a
    brand-new project the user did not mean to create. To soften the failure
    we attach ``Did you mean: ...`` suggestions when similar project names
    exist in the workspace.
    """
    try:
        return rest_helpers.resolve_project_id_by_name(
            client.rest_client, project_name=to_project
        )
    except ApiError as exc:
        if exc.status_code == 404:
            suggestions = _suggest_project_names(client, to_project)
            message = f"Destination project '{to_project}' does not exist."
            if suggestions:
                message += f" Did you mean: {', '.join(repr(s) for s in suggestions)}?"
            message += " Create it via the UI or SDK, then re-run."
            raise ProjectNotFoundError(message) from exc
        raise


def _suggest_project_names(client: opik.Opik, name: str) -> List[str]:
    """Return up to 3 closest project-name matches for ``name``.

    Best-effort with narrow exception handling: an ``ApiError`` during the
    lookup just degrades to no suggestions (we'd rather raise the original
    404 from the caller than a secondary error that obscures the real issue),
    but unexpected errors are logged so they're still visible to operators.
    """
    try:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: client.rest_client.projects.find_projects(
                page=1, size=_PROJECT_SUGGESTION_PAGE_SIZE
            )
        )
    except ApiError:
        return []
    except Exception as exc:
        LOGGER.warning(
            "Project-suggestion lookup failed unexpectedly while resolving "
            "missing project %r: %s",
            name,
            exc.__class__.__name__,
        )
        return []
    candidates = [p.name for p in page.content if p.name]
    return difflib.get_close_matches(name, candidates, n=3, cutoff=0.6)


def name_taken_in_workspace(
    client: opik.Opik,
    name: str,
    *,
    ignore_dataset_id: Optional[str] = None,
) -> Optional[str]:
    """Return a description of the colliding project, or ``None``.

    Opik enforces dataset names workspace-wide (BE constraint
    ``UNIQUE (workspace_id, name)`` on the ``datasets`` table), so the
    pre-flight scans the workspace for any row holding the name.
    ``ignore_dataset_id`` lets callers exclude the source dataset from
    the check (it'll get renamed before the destination is created, so
    its current name doesn't conflict).

    Pagination: iterates every page until we either find a true collision
    (early-exit) or exhaust the results — so collisions don't slip through
    just because they live past the first page of name-matches.

    The returned string is a human-readable label for the colliding project
    (its name when resolvable, else ``"another project"``) suitable for
    ``ConflictError`` messages.
    """
    for row in _iter_dataset_pages(client, name=name):
        if row.name != name:
            continue
        if ignore_dataset_id is not None and row.id == ignore_dataset_id:
            continue
        project_name = _project_name_for_row(client, row)
        if project_name:
            return f"project '{project_name}'"
        return "another project"
    return None
