"""Name resolution for ``opik migrate prompt``.

Prompts are addressed by name. Unlike datasets, prompt names are
workspace-unique (BE liquibase migration
``000004_add_prompt_library_tables.sql`` enforces ``unique(workspace_id,
name)``), so a workspace lookup either yields one row or zero — no
ambiguity disambiguation needed, no ``--from-project`` flag at the CLI
surface.
"""

from __future__ import annotations

import difflib
import logging
from dataclasses import dataclass
from typing import Any, Iterable, List, Optional

import opik
from opik.api_objects import rest_helpers
from opik.rest_api.core.api_error import ApiError

from ..errors import ConflictError, ProjectNotFoundError, PromptNotFoundError

LOGGER = logging.getLogger(__name__)

_PROJECT_SUGGESTION_PAGE_SIZE = 100
_FIND_PROMPTS_PAGE_SIZE = 100


@dataclass(frozen=True)
class ResolvedPrompt:
    """Snapshot of the source prompt taken at plan time.

    Carries every container-level field the planner needs to forward into
    the destination prompt + the source rename PUT. Version-level fields
    (``template``, ``type``, ``template_structure``, ``commit``, ...) live
    on ``PromptVersionDetail`` and are read per-version during replay.

    ``template_structure`` is a container-level read on the BE
    (``PromptPublic.template_structure``: ``"text" | "chat"``); it's
    surfaced on every version write so the BE can validate compatibility.
    The planner forwards the source's structure unchanged.
    """

    id: str
    name: str
    project_id: Optional[str]
    project_name: Optional[str]
    description: Optional[str]
    tags: Optional[List[str]]
    template_structure: Optional[str]


def _iter_prompt_pages(
    client: opik.Opik,
    *,
    name: Optional[str] = None,
) -> Iterable[Any]:
    """Yield every prompt row matching the filters, paginating to exhaustion.

    Stays on the low-level Fern surface (``client.rest_client.prompts``):
    the high-level ``opik.api_objects.prompt`` wrapper returns ``Prompt``
    SDK objects that drop ``project_id`` / ``template_structure``, both of
    which the planner needs.
    """
    rest_client = client.rest_client
    page_idx = 1
    while True:
        page = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda p=page_idx: rest_client.prompts.get_prompts(
                page=p,
                size=_FIND_PROMPTS_PAGE_SIZE,
                name=name,
            )
        )
        content = getattr(page, "content", None) or []
        if not content:
            return
        for row in content:
            yield row
        if len(content) < _FIND_PROMPTS_PAGE_SIZE:
            return
        page_idx += 1


def _project_name_for_row(client: opik.Opik, row: Any) -> Optional[str]:
    """Resolve a prompt row's ``project_id`` to a human-readable name.

    Workspace-scoped prompts (``project_id`` null) return None. Recoverable
    ``ApiError`` (typically 404 for a project deleted out from under us)
    also returns None — the collision message degrades to "another project"
    rather than the lookup failing.
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
        return None
    except Exception as exc:
        LOGGER.warning(
            "Unexpected error resolving project id %s to a name: %s",
            project_id,
            exc.__class__.__name__,
        )
        return None


def resolve_source_prompt(client: opik.Opik, name: str) -> ResolvedPrompt:
    """Resolve a prompt name to a single ``ResolvedPrompt``.

    Prompt names are workspace-unique (BE constraint), so we walk every
    ``get_prompts`` page filtering by name and expect at most one match.
    Zero matches raise ``PromptNotFoundError`` with a workspace-scope label.

    No project-scoping filter exists on the find endpoint, which is fine —
    workspace uniqueness makes a project filter pointless for resolution.
    """
    matches: List[Any] = []
    for row in _iter_prompt_pages(client, name=name):
        if row.name != name:
            continue
        matches.append(row)

    if not matches:
        raise PromptNotFoundError(f"Prompt '{name}' not found in the workspace.")

    # Workspace uniqueness means >1 matches would imply a BE invariant
    # violation; surface it explicitly rather than silently picking one.
    if len(matches) > 1:
        raise ConflictError(
            f"Prompt name '{name}' matched {len(matches)} rows — the workspace "
            "uniqueness invariant appears to be violated. Aborting; investigate "
            "via the UI before retrying."
        )

    row = matches[0]
    return ResolvedPrompt(
        id=row.id,
        name=row.name,
        project_id=getattr(row, "project_id", None),
        project_name=_project_name_for_row(client, row),
        description=getattr(row, "description", None),
        tags=getattr(row, "tags", None),
        template_structure=getattr(row, "template_structure", None),
    )


def ensure_destination_project_exists(
    client: opik.Opik,
    to_project: str,
) -> str:
    """Resolve and return the ``to_project`` ID, or raise ``ProjectNotFoundError``.

    Same behaviour as the dataset planner: fail fast on typos rather than
    auto-creating a project, and soften the failure with ``Did you mean: ...``
    suggestions when close-name workspace projects exist.
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
    ignore_prompt_id: Optional[str] = None,
) -> Optional[str]:
    """Return a description of the colliding prompt, or ``None``.

    Used by the planner's pre-flight to detect rename collisions:
    ``<source>_v1`` must be free workspace-wide before the rename PUT runs,
    or the BE returns 409. ``ignore_prompt_id`` excludes the source itself
    (it's about to be renamed).

    Returns a human-readable label for the colliding row's project name
    (or ``"another project"`` when the prompt is workspace-scoped or the
    project lookup fails), suitable for ``ConflictError`` messages.
    """
    for row in _iter_prompt_pages(client, name=name):
        if row.name != name:
            continue
        if ignore_prompt_id is not None and row.id == ignore_prompt_id:
            continue
        project_name = _project_name_for_row(client, row)
        if project_name:
            return f"project '{project_name}'"
        return "another project"
    return None
