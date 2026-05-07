"""Name resolution for ``opik migrate``.

Datasets are addressed by name in the UI, but the workspace allows the same
dataset name across different projects. ``resolve_source`` enforces names-as-
identifiers UX: a single hit wins, zero hits raise ``DatasetNotFoundError``,
multiple hits (workspace-scoped lookup) raise ``AmbiguityError`` listing the
project paths so the user can disambiguate with ``--from-project``.
"""

from __future__ import annotations

import difflib
from dataclasses import dataclass
from typing import List, Optional

from opik.api_objects import rest_helpers
from opik.rest_api import OpikApi
from opik.rest_api.core.api_error import ApiError

from .errors import AmbiguityError, DatasetNotFoundError, ProjectNotFoundError

# Cap the candidate list we feed to difflib. Workspaces with >100 projects
# are uncommon, and the suggestion quality plateaus quickly past that.
_PROJECT_SUGGESTION_PAGE_SIZE = 100


@dataclass(frozen=True)
class ResolvedDataset:
    id: str
    name: str
    project_name: Optional[str]
    description: Optional[str]
    # Type ('dataset' or 'evaluation_suite' for test suites). Slice 1 only
    # supports plain datasets; the planner refuses 'evaluation_suite' since
    # suite-level evaluators/execution_policy live as a versioned config that
    # this slice does not replicate.
    type: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]


def resolve_source(
    rest_client: OpikApi,
    name: str,
    from_project: Optional[str],
) -> ResolvedDataset:
    """Resolve ``name`` (optionally scoped to ``from_project``) to a single dataset.

    ``from_project=None`` means workspace-scoped lookup — datasets created
    without an explicit project. Multiple workspace-scoped matches with the
    same name shouldn't normally happen (the BE enforces uniqueness within a
    project), but workspace-scoped + project-scoped collisions are possible
    and we surface them as ``AmbiguityError``.
    """
    project_id = rest_helpers.resolve_project_id_by_name_optional(
        rest_client, project_name=from_project
    )

    page = rest_client.datasets.find_datasets(
        page=1, size=100, project_id=project_id, name=name
    )
    matches: List[ResolvedDataset] = [
        ResolvedDataset(
            id=d.id,
            name=d.name,
            project_name=from_project,
            description=d.description,
            type=getattr(d, "type", None),
            visibility=getattr(d, "visibility", None),
            tags=getattr(d, "tags", None),
        )
        for d in page.content
        if d.name == name
    ]

    if not matches:
        scope = f"project '{from_project}'" if from_project else "the workspace"
        raise DatasetNotFoundError(f"Dataset '{name}' not found in {scope}.")

    if len(matches) > 1:
        raise AmbiguityError(
            f"Dataset name '{name}' matched {len(matches)} datasets. "
            "Re-run with --from-project to disambiguate."
        )

    return matches[0]


def ensure_destination_project_exists(
    rest_client: OpikApi,
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
            rest_client, project_name=to_project
        )
    except ApiError as exc:
        if exc.status_code == 404:
            suggestions = _suggest_project_names(rest_client, to_project)
            message = f"Destination project '{to_project}' does not exist."
            if suggestions:
                message += f" Did you mean: {', '.join(repr(s) for s in suggestions)}?"
            message += " Create it via the UI or SDK, then re-run."
            raise ProjectNotFoundError(message) from exc
        raise


def _suggest_project_names(rest_client: OpikApi, name: str) -> List[str]:
    """Return up to 3 closest project-name matches for ``name``.

    Best-effort: any error during suggestion lookup degrades silently to no
    suggestions — we'd rather raise the original 404 than a secondary error
    that obscures the real issue.
    """
    try:
        page = rest_client.projects.find_projects(
            page=1, size=_PROJECT_SUGGESTION_PAGE_SIZE
        )
        candidates = [p.name for p in page.content if p.name]
    except Exception:
        return []
    return difflib.get_close_matches(name, candidates, n=3, cutoff=0.6)


def name_taken_in_workspace(
    rest_client: OpikApi,
    name: str,
    *,
    ignore_dataset_id: Optional[str] = None,
) -> Optional[str]:
    """Return the project name of a workspace-wide collision, or ``None``.

    Opik enforces dataset names workspace-wide (not per-project), so the
    pre-flight has to scan without a ``project_id`` filter. ``ignore_dataset_id``
    lets callers exclude the source dataset from the check (it'll get renamed
    before the destination is created, so its current name doesn't conflict).
    """
    page = rest_client.datasets.find_datasets(page=1, size=10, name=name)
    for d in page.content:
        if d.name != name:
            continue
        if ignore_dataset_id is not None and d.id == ignore_dataset_id:
            continue
        # The Fern model carries project_name on dataset rows; fall back to
        # the id when the name is unavailable for older payloads.
        return getattr(d, "project_name", None) or "another project"
    return None
