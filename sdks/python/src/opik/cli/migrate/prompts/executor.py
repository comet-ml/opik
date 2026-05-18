"""Executor for the prompt ``MigrationPlan``.

The audit-bracketed for-loop and dry-run "record planned" loop are
generic across entity types and live in ``cli/migrate/_base.py``. This
module owns prompt-specific action dispatch (``_apply_action``) plus the
two helpers ``_create_destination_prompt`` and
``_replay_prompt_versions`` that Slice 7 (OPIK-6575) imports for the
dataset-cascade-prompts integration.
"""

from __future__ import annotations

import logging
from typing import Any, Dict, Optional

import opik
from opik.api_objects import rest_helpers
from opik.rest_api import OpikApi
from rich.console import Console
from rich.progress import BarColumn, Progress, TaskProgressColumn, TextColumn

from ..audit import AuditLog
from .._base import execute_plan_loop, record_planned_loop
from .planner import (
    CreateDestination,
    MigrationPlan,
    RenameSource,
    ReplayVersions,
)
from .version_replay import ReplayResult, replay_all_prompt_versions

LOGGER = logging.getLogger(__name__)
_console = Console()


def execute_plan(
    client: opik.Opik,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Apply ``plan`` against ``client``, recording each action in ``audit``."""
    rest_client = client.rest_client

    def _apply(action: Any) -> None:
        _apply_action(client, rest_client, action, plan=plan, audit=audit)

    execute_plan_loop(
        plan.actions,
        audit,
        apply_fn=_apply,
        details_fn=_action_details,
    )


def record_planned(plan: MigrationPlan, audit: AuditLog) -> None:
    """Record every planned action with status=planned (used for dry-run)."""
    record_planned_loop(plan.actions, audit, details_fn=_action_details)


def _apply_action(
    client: opik.Opik,
    rest_client: OpikApi,
    action: object,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    if isinstance(action, RenameSource):
        # Re-pass description / tags so the BE doesn't wipe them on the
        # rename PUT. ``update_prompt`` SQL: ``description = :bean.description``
        # has no COALESCE, so omitting description nulls it. Tags use
        # COALESCE; we re-pass for symmetry.
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.prompts.update_prompt(
                id=action.source_id,
                name=action.to_name,
                description=action.description,
                tags=action.tags,
            )
        )
    elif isinstance(action, CreateDestination):
        _create_destination_prompt(
            rest_client,
            name=action.name,
            project_name=action.project_name,
            description=action.description,
            tags=action.tags,
            template_structure=action.template_structure,
        )
    elif isinstance(action, ReplayVersions):
        _replay_prompt_versions(
            rest_client, action, plan=plan, audit=audit, console=_console
        )
    else:
        raise TypeError(f"Unknown migration action: {type(action).__name__}")


def _create_destination_prompt(
    rest_client: OpikApi,
    *,
    name: str,
    project_name: str,
    description: Optional[str],
    tags: Optional[Any],
    template_structure: Optional[str],
) -> None:
    """Mint a bare destination prompt container without auto-creating a v1.

    Exposed as a module-level helper so Slice 7 (dataset cascade pulling
    prompts) can import it verbatim.

    Critically: NO ``template`` is forwarded. The BE auto-creates v1 only
    when ``!StringUtils.isEmpty(promptRequest.template())``, so the bare
    container is created here and every source version (including v1) is
    minted by ``_replay_prompt_versions`` so client-supplied ``commit``
    values are carried verbatim.

    ``template_structure`` IS forwarded because the BE persists it on the
    prompt container (``prompts.template_structure``), not per version.
    """
    create_kwargs: Dict[str, Any] = {
        "name": name,
        "project_name": project_name,
    }
    if description is not None:
        create_kwargs["description"] = description
    if tags is not None:
        create_kwargs["tags"] = tags
    if template_structure is not None:
        create_kwargs["template_structure"] = template_structure
    rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: rest_client.prompts.create_prompt(**create_kwargs)
    )


def _replay_prompt_versions(
    rest_client: OpikApi,
    action: ReplayVersions,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
    console: Optional[Console] = None,
) -> ReplayResult:
    """Drive the per-version replay loop with a Rich progress bar.

    Exposed as a module-level helper so Slice 7 can import it verbatim;
    ``console=None`` skips the progress bar (useful when the cascade owns
    a different UI surface).

    Updates ``plan.prompt_version_id_remap`` from the replay result so the
    plan carries the source→dest version-id map for downstream consumers.
    """
    if console is None:
        result = replay_all_prompt_versions(
            rest_client,
            source_prompt_id=action.source_prompt_id,
            source_name_after_rename=action.source_name_after_rename,
            source_project_name=action.source_project_name,
            dest_name=action.dest_name,
            dest_project_name=action.dest_project_name,
            template_structure=action.template_structure,
            audit=audit,
        )
        plan.prompt_version_id_remap.update(result.prompt_version_id_remap)
        return result

    with Progress(
        TextColumn("[bold blue]Replaying prompt versions"),
        BarColumn(),
        TaskProgressColumn(),
        TextColumn("{task.description}"),
        console=console,
        transient=False,
    ) as progress:
        task_id: Optional[int] = None

        def _on_version_start(completed: int, total: int, label: str) -> None:
            nonlocal task_id
            description = f"→ {action.dest_name} · {label} ({completed + 1}/{total})"
            if task_id is None:
                task_id = progress.add_task(description, total=total)
            else:
                progress.update(task_id, completed=completed, description=description)

        result = replay_all_prompt_versions(
            rest_client,
            source_prompt_id=action.source_prompt_id,
            source_name_after_rename=action.source_name_after_rename,
            source_project_name=action.source_project_name,
            dest_name=action.dest_name,
            dest_project_name=action.dest_project_name,
            template_structure=action.template_structure,
            audit=audit,
            progress_callback=_on_version_start,
        )

        # Callback fires BEFORE each version, so the last update leaves
        # the bar at N-1; advance to N once the loop returns successfully.
        if task_id is not None:
            progress.update(task_id, completed=result.versions_replayed)

    plan.prompt_version_id_remap.update(result.prompt_version_id_remap)
    return result


def _action_details(action: object) -> Dict[str, Any]:
    if isinstance(action, RenameSource):
        return {
            "type": "rename_source",
            "entity": "prompt",
            "id": action.source_id,
            "from": action.from_name,
            "to": action.to_name,
        }
    if isinstance(action, CreateDestination):
        return {
            "type": "create_destination",
            "entity": "prompt",
            "name": action.name,
            "project": action.project_name,
        }
    if isinstance(action, ReplayVersions):
        return {
            "type": "replay_versions",
            "entity": "prompt",
            "from_prompt": action.source_name_after_rename,
            "from_project": action.source_project_name,
            "to_prompt": action.dest_name,
            "to_project": action.dest_project_name,
        }
    raise TypeError(f"Unknown migration action: {type(action).__name__}")
