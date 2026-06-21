"""Name resolution for ``opik migrate prompt``.

Entity-agnostic helpers (pagination, project-name lookup, collision
check, project preflight) live in ``cli/migrate/_resolver.py``. This
module keeps only prompt-specific glue: the ``ResolvedPrompt`` dataclass
and the ``resolve_source_prompt`` / ``name_taken_in_workspace``
wrappers that bind the prompt Fern surface.

Prompt names are workspace-unique (BE liquibase migration
``000004_add_prompt_library_tables.sql`` enforces
``UNIQUE (workspace_id, name)``), so a name lookup yields at most one
row. ``--from-project`` is still useful as an **optional** filter:

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
    ensure_destination_project_exists as _ensure_destination_project_exists,
    make_list_fn,
    name_taken_in_workspace as _name_taken_in_workspace,
    project_name_for_row,
    resolve_unique_source_by_name,
)
from ..errors import ConflictError, PromptNotFoundError


@dataclass(frozen=True)
class ResolvedPrompt:
    """Snapshot of the source prompt taken at plan time.

    Carries every container-level field the planner needs to forward
    into the destination prompt + the source rename PUT. Version-level
    fields (``template``, ``type``, ``template_structure``, ``commit``,
    …) live on ``PromptVersionDetail`` and are read per-version during
    replay.

    ``template_structure`` is a container-level read on the BE
    (``PromptPublic.template_structure``: ``"text" | "chat"``); the
    planner forwards the source's structure unchanged so the BE can
    validate compatibility on every version write.
    """

    id: str
    name: str
    project_id: Optional[str]
    project_name: Optional[str]
    description: Optional[str]
    tags: Optional[List[str]]
    template_structure: Optional[str]


def _prompts_list_fn(
    client: opik.Opik, *, name: Optional[str], project_id: Optional[str]
) -> Callable[[int, int], Any]:
    """Build the prompt ``(page, size) -> response`` closure.

    Stays on the low-level Fern surface
    (``client.rest_client.prompts.get_prompts``): the high-level
    ``opik.api_objects.prompt`` wrapper returns SDK ``Prompt`` objects
    that drop ``project_id`` / ``template_structure``, both of which
    the planner needs.

    ``project_id`` filters the BE query when provided (smaller result
    set on workspaces with many entities); ``None`` is workspace-wide.
    """
    return make_list_fn(
        client.rest_client.prompts.get_prompts,
        name=name,
        project_id=project_id,
    )


def resolve_source_prompt(
    client: opik.Opik,
    name: str,
    from_project: Optional[str] = None,
) -> ResolvedPrompt:
    """Resolve ``name`` (optionally scoped to ``from_project``) to one prompt.

    ``from_project=None`` does a workspace-wide lookup. When provided,
    the BE filters by the resolved ``project_id`` so the result set is
    smaller and the not-found error message names the project. Workspace
    name uniqueness means a workspace lookup also yields at most one
    row; the flag is primarily a perf / UX hint when the user knows the
    source project.

    Raises ``PromptNotFoundError`` on zero matches. The (architecturally
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
        list_fn=_prompts_list_fn(client, name=name, project_id=project_id),
        name=name,
        not_found_error=PromptNotFoundError,
        invariant_violated_error=ConflictError,
        entity_label="prompt",
        scope_label=scope_label,
    )
    return ResolvedPrompt(
        id=row.id,
        name=row.name,
        project_id=getattr(row, "project_id", None),
        project_name=project_name_for_row(client, row),
        description=getattr(row, "description", None),
        tags=getattr(row, "tags", None),
        template_structure=getattr(row, "template_structure", None),
    )


def ensure_destination_project_exists(client: opik.Opik, to_project: str) -> str:
    """Resolve and return ``to_project`` ID, or raise ``ProjectNotFoundError``."""
    return _ensure_destination_project_exists(client, to_project)


def name_taken_in_workspace(
    client: opik.Opik,
    name: str,
    *,
    ignore_prompt_id: Optional[str] = None,
) -> Optional[str]:
    """Workspace-wide name collision check for the rename PUT pre-flight.

    Always scopes workspace-wide (``project_id=None``): the rename
    target must be free across the entire workspace because
    ``(workspace_id, name)`` is the BE's unique key. ``ignore_prompt_id``
    excludes the source prompt itself from the check (it's about to be
    renamed). Returns a human-readable label for the colliding project,
    or ``None`` when the name is free.
    """
    return _name_taken_in_workspace(
        client,
        list_fn=_prompts_list_fn(client, name=name, project_id=None),
        name=name,
        ignore_id=ignore_prompt_id,
    )


__all__ = [
    "ResolvedPrompt",
    "ensure_destination_project_exists",
    "name_taken_in_workspace",
    "resolve_source_prompt",
]
