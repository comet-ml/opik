"""Action planning for ``opik migrate prompt``.

Mirrors ``cli/migrate/datasets/planner.py``: builds an ordered
``MigrationPlan`` of typed action records that the executor applies in
sequence. The same separation lets ``--dry-run`` reuse the planner with
no side effects.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Dict, List, Optional

import opik

from .._base import BaseMigrationPlan
from ..errors import ConflictError
from .resolver import (
    ResolvedPrompt,
    ensure_destination_project_exists,
    name_taken_in_workspace,
    resolve_source_prompt,
)

# Hardcoded suffix appended to the source prompt's name during the rename
# step. Matches the dataset planner's convention so the audit log and CLI
# output read uniformly.
SOURCE_SUFFIX = "_v1"


@dataclass(frozen=True)
class RenameSource:
    """Rename the source prompt to ``<name>_v1`` to free the destination name.

    Prompt names are workspace-unique on the BE
    (``unique(workspace_id, name)``); the destination needs the source's
    original name, so the rename has to happen first.

    The BE's ``PATCH /v1/private/prompts/{id}`` only accepts ``name``,
    ``description``, and ``tags`` as writable fields. ``description`` is
    overwritten unconditionally on the SQL UPDATE (see ``PromptDAO``
    UPDATE: ``description = :bean.description`` — no COALESCE) so we
    re-pass the source description verbatim to avoid wiping it. ``tags``
    uses COALESCE, but we re-pass them for symmetry.
    """

    source_id: str
    from_name: str
    to_name: str
    description: Optional[str]
    tags: Optional[List[str]]


@dataclass(frozen=True)
class CreateDestination:
    """Mint a bare destination prompt container under ``--to-project``.

    Critically, ``template`` is NOT included: ``PromptService.create``
    only auto-mints v1 when ``!StringUtils.isEmpty(promptRequest.template())``.
    Omitting the template gives us an empty container, after which
    ``ReplayVersions`` mints every source version (including v1) via
    ``create_prompt_version`` so client-supplied ``commit`` values are
    preserved verbatim.

    ``template_structure`` IS forwarded (it's a container-level field on
    the BE, not a version field, and would otherwise default).
    """

    name: str
    project_name: str
    description: Optional[str]
    tags: Optional[List[str]]
    template_structure: Optional[str]


@dataclass(frozen=True)
class ReplayVersions:
    """Replay every source prompt version onto the destination chronologically.

    Iterates source versions oldest-first (the BE returns newest-first;
    the replay loop reverses) and POSTs each via
    ``create_prompt_version`` with the source's ``commit`` carried
    verbatim. The destination prompt id is fresh, so the BE's
    ``unique(workspace_id, prompt_id, commit)`` constraint never trips.

    The executor populates ``MigrationPlan.prompt_version_id_remap``
    (source version id -> dest version id) after this action runs; Slice
    7 (OPIK-6575) reads that map to remap experiment FK references to
    prompt versions.
    """

    source_prompt_id: str
    source_name_after_rename: str
    source_project_name: Optional[str]
    dest_name: str
    dest_project_name: str
    template_structure: Optional[str]


@dataclass(kw_only=True)
class MigrationPlan(BaseMigrationPlan):
    """Prompt-specific migration plan.

    Inherits ``source`` / ``actions`` from ``BaseMigrationPlan`` and
    narrows ``source`` to ``ResolvedPrompt``. The action list is the
    prompt-specific (``RenameSource``, ``CreateDestination``,
    ``ReplayVersions``) triple.

    ``prompt_version_id_remap`` is populated by the executor after a
    successful ``ReplayVersions`` run so Slice 7 (OPIK-6575) can remap
    experiment FK references to prompt versions.

    There is no ``prompt_id_remap``: the destination prompt is resolved
    by name + project at Slice 7's execution time, mirroring how the
    dataset experiment cascade resolves the destination dataset
    (``cli/migrate/datasets/experiments.py`` carries ``dest_name`` +
    ``dest_project_name`` and looks up the row at apply time).
    Workspace name uniqueness (``UNIQUE (workspace_id, name)`` on
    prompts) makes the lookup unambiguous once the rename has happened.
    """

    source: ResolvedPrompt
    target_name: str
    to_project: str
    prompt_version_id_remap: Dict[str, str] = field(default_factory=dict)


def build_prompt_plan(
    client: opik.Opik,
    name: str,
    to_project: str,
    from_project: Optional[str] = None,
) -> MigrationPlan:
    """Build the ordered action list for migrating one prompt.

    Ordering invariant: the source rename always precedes the destination
    create, so the workspace-unique-name constraint
    (``unique(workspace_id, name)``) never trips. The destination keeps
    the source's original name.

    ``from_project`` is an optional source-scope hint (perf + clearer
    error message); ``None`` does a workspace-wide source lookup.

    The plan emits, in order:

      1. ``RenameSource`` — frees the source's name for the destination.
      2. ``CreateDestination`` — creates a bare destination prompt
         container (no template, so the BE does NOT auto-mint a v1).
      3. ``ReplayVersions`` — replays every source version onto the
         destination, populating ``plan.prompt_version_id_remap``.
    """
    # Fail fast if --to-project doesn't exist. Catches typos before any
    # rename/create work and prevents auto-creating a stray project.
    ensure_destination_project_exists(client, to_project)

    source = resolve_source_prompt(client, name, from_project)

    name_after_rename = f"{source.name}{SOURCE_SUFFIX}"

    # Prompt names are workspace-unique; the rename target has to be free
    # workspace-wide (excluding the source row itself, which is about to
    # be renamed). Without this check the rename PUT would 409 mid-flight.
    collision = name_taken_in_workspace(
        client, name_after_rename, ignore_prompt_id=source.id
    )
    if collision:
        raise ConflictError(
            f"Cannot rename source to '{name_after_rename}' — that name is "
            f"already used by a prompt in {collision}. "
            "Rename or delete the conflicting prompt and re-run."
        )

    plan = MigrationPlan(
        source=source,
        target_name=source.name,
        to_project=to_project,
    )

    plan.actions.append(
        RenameSource(
            source_id=source.id,
            from_name=source.name,
            to_name=name_after_rename,
            description=source.description,
            tags=source.tags,
        )
    )

    plan.actions.append(
        CreateDestination(
            name=source.name,
            project_name=to_project,
            description=source.description,
            tags=source.tags,
            template_structure=source.template_structure,
        )
    )

    plan.actions.append(
        ReplayVersions(
            source_prompt_id=source.id,
            source_name_after_rename=name_after_rename,
            source_project_name=source.project_name,
            dest_name=source.name,
            dest_project_name=to_project,
            template_structure=source.template_structure,
        )
    )

    return plan
