"""Name resolution for ``opik migrate prompt``.

Entity-agnostic helpers (pagination, project-name lookup, collision
check, project preflight) live in ``cli/migrate/_resolver.py``. This
module keeps only prompt-specific glue: the ``ResolvedPrompt`` dataclass
and the ``resolve_source_prompt`` / ``name_taken_in_workspace``
wrappers that bind the prompt Fern surface.

Prompt names are workspace-unique (BE liquibase migration
``000004_add_prompt_library_tables.sql`` enforces
``UNIQUE (workspace_id, name)``), so a workspace lookup either yields
one row or zero — no ambiguity disambiguation needed, no
``--from-project`` flag at the CLI surface.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any, Callable, List, Optional

import opik

from .._resolver import (
    ensure_destination_project_exists as _ensure_destination_project_exists,
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
    client: opik.Opik, *, name: Optional[str]
) -> Callable[[int, int], Any]:
    """Build a ``(page, size) -> response`` closure for ``get_prompts``.

    Stays on the low-level Fern surface (``client.rest_client.prompts``):
    the high-level ``opik.api_objects.prompt`` wrapper returns SDK
    ``Prompt`` objects that drop ``project_id`` / ``template_structure``,
    both of which the planner needs.
    """
    rest_client = client.rest_client

    def _list(page: int, size: int) -> Any:
        return rest_client.prompts.get_prompts(page=page, size=size, name=name)

    return _list


def resolve_source_prompt(client: opik.Opik, name: str) -> ResolvedPrompt:
    """Resolve ``name`` to a single prompt, or raise.

    Workspace uniqueness means a name lookup yields at most one row.
    Zero matches raise ``PromptNotFoundError``; the (unreachable)
    >1-matches case raises ``ConflictError`` with an
    "invariant violated" message.
    """
    row = resolve_unique_source_by_name(
        client,
        list_fn=_prompts_list_fn(client, name=name),
        name=name,
        not_found_error=PromptNotFoundError,
        invariant_violated_error=ConflictError,
        entity_label="prompt",
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

    Returns a human-readable label for the colliding project (or
    ``"another project"`` when workspace-scoped / project lookup fails),
    or ``None`` when the name is free. ``ignore_prompt_id`` excludes
    the source prompt itself from the check (it's about to be renamed).
    """
    return _name_taken_in_workspace(
        client,
        list_fn=_prompts_list_fn(client, name=name),
        name=name,
        ignore_id=ignore_prompt_id,
    )


__all__ = [
    "ResolvedPrompt",
    "ensure_destination_project_exists",
    "name_taken_in_workspace",
    "resolve_source_prompt",
]
