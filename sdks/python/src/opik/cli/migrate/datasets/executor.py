"""Executor for the dataset ``MigrationPlan``.

The audit-bracketed for-loop and the dry-run "record planned" loop are
generic across entity types and live in ``cli/migrate/_base.py``. This
module owns dataset-specific action dispatch (``_apply_action``) and the
audit-log payload shape per action type (``_action_details``).
"""

from __future__ import annotations

import logging
from typing import Any, Dict, List, Optional

import opik
from opik.api_objects import rest_helpers
from opik.rest_api import OpikApi
from rich.console import Console
from rich.progress import BarColumn, Progress, TaskProgressColumn, TextColumn

from ..audit import AuditLog
from .._base import execute_plan_loop, record_planned_loop
from .experiments import cascade_experiments
from .planner import (
    CascadeExperiments,
    CopyCurrentItems,
    CopyTestSuiteConfig,
    CreateDestination,
    MigrationPlan,
    RenameSource,
    ReplayVersions,
)
from .version_replay import (
    _evaluators_payload,
    _execution_policy_payload,
    replay_all_versions,
)

LOGGER = logging.getLogger(__name__)
_console = Console()


def execute_plan(
    client: opik.Opik,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Apply ``plan`` against ``client``, recording each action in ``audit``.

    Delegates the audit-bracketed loop to ``_base.execute_plan_loop`` and
    supplies the dataset-specific apply / details closures. The closure
    captures ``plan`` and ``audit`` so the ``ReplayVersions`` branch can
    stash version_remap / item_id_remap onto the plan and emit per-version
    audit sub-records.
    """
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
    # Workspace-mutating REST writes are wrapped with the SDK's 429-aware
    # retry helper so a transient rate limit doesn't abort a half-finished
    # migration. Reads (find_datasets, find_projects, retrieve_project,
    # list_dataset_versions) stay unwrapped — failing fast on 429 there is a
    # better signal than silent retry.
    if isinstance(action, RenameSource):
        # Re-pass description/visibility/tags so the BE doesn't wipe them on
        # the rename PUT (description is silently nulled when omitted).
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.datasets.update_dataset(
                id=action.source_id,
                name=action.to_name,
                description=action.description,
                visibility=action.visibility,
                tags=action.tags,
            )
        )
    elif isinstance(action, CreateDestination):
        # Forward all dataset-level metadata so the target inherits the
        # source's description, visibility, tags, and (for test suites) type.
        kwargs: Dict[str, Any] = dict(
            name=action.name,
            description=action.description,
            project_name=action.project_name,
            visibility=action.visibility,
            tags=action.tags,
        )
        if action.type is not None:
            kwargs["type"] = action.type
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.datasets.create_dataset(**kwargs)
        )
    elif isinstance(action, CopyCurrentItems):
        # __internal_api__insert_items_as_dataclasses__ already wraps every
        # internal batch with the rate-limit helper, so no extra wrapping here.
        _copy_items(client, action)
    elif isinstance(action, CopyTestSuiteConfig):
        _copy_test_suite_config(rest_client, action)
    elif isinstance(action, ReplayVersions):
        _replay_versions(rest_client, action, plan=plan, audit=audit)
    elif isinstance(action, CascadeExperiments):
        _cascade_experiments(client, rest_client, action, plan=plan, audit=audit)
    else:
        raise TypeError(f"Unknown migration action: {type(action).__name__}")


def _copy_items(client: opik.Opik, action: CopyCurrentItems) -> None:
    from opik.api_objects.dataset import dataset_item

    source = client.get_dataset(
        action.source_name_after_rename, project_name=action.source_project_name
    )
    # Stream as dataclasses (DatasetItem) so per-item description, source,
    # trace_id, span_id, evaluators, and execution_policy round-trip. The
    # public `get_items()` returns dicts that strip those top-level fields.
    items = list(source.__internal_api__stream_items_as_dataclasses__())
    if not items:
        return

    # Stream is newest-first; insert preserves call order; UI displays
    # newest-first. Reversing once makes target display order match source
    # display order at any scale.
    items.reverse()

    # Build fresh DatasetItem instances for the target so each gets a
    # server-fresh id (the source ids belong to the source dataset). All
    # other top-level fields and the user-defined `data` content are copied
    # verbatim — that's what restores fidelity vs the prior dict round-trip.
    rebuilt: List[dataset_item.DatasetItem] = [
        dataset_item.DatasetItem(
            trace_id=item.trace_id,
            span_id=item.span_id,
            source=item.source,
            description=item.description,
            evaluators=item.evaluators,
            execution_policy=item.execution_policy,
            **item.get_content(),
        )
        for item in items
    ]

    dest = client.get_dataset(action.dest_name, project_name=action.dest_project_name)

    # Single dataclass-form insert call (not a chunked loop):
    # __internal_api__insert_items_as_dataclasses__ coalesces all internal HTTP
    # batches under one batch_group_id, producing exactly ONE dataset version on
    # the target. A CLI-level chunked loop would generate one version per chunk
    # — wrong for slice 1's "current items only" contract and would corrupt the
    # starting state for slice 2's version-history replay.
    with _console.status(
        f"Copying {len(rebuilt):,} items → {action.dest_name}…",
        spinner="dots",
    ):
        dest.__internal_api__insert_items_as_dataclasses__(rebuilt)


def _replay_versions(
    rest_client: OpikApi,
    action: ReplayVersions,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Resolve target id and run the per-version replay loop.

    The destination dataset was created by the prior ``CreateDestination``
    action with zero versions. ``replay_all_versions`` handles the cold
    start itself by minting target v1 via the BE-natural write path that
    matches the source v1's shape (content via
    ``create_or_update_dataset_items`` or config-only via
    ``apply_dataset_item_changes(base_version=null, override=true)``) so
    target version count == source version count — no leading empty seed.
    """
    dest = rest_client.datasets.get_dataset_by_identifier(
        dataset_name=action.dest_name, project_name=action.dest_project_name
    )

    # Rich Progress bar driven by the per-version callback that
    # ``replay_all_versions`` invokes before each version begins. The task is
    # created lazily on the first callback (we don't know the total until
    # source-version listing finishes) and advanced once per version. Keeping
    # the UI here means the algorithmic core in ``version_replay`` stays
    # console-agnostic.
    with Progress(
        TextColumn("[bold blue]Replaying versions"),
        BarColumn(),
        TaskProgressColumn(),
        TextColumn("{task.description}"),
        console=_console,
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

        result = replay_all_versions(
            rest_client,
            source_dataset_id=action.source_dataset_id,
            source_name_after_rename=action.source_name_after_rename,
            source_project_name=action.source_project_name,
            dest_dataset_id=dest.id,
            dest_name=action.dest_name,
            dest_project_name=action.dest_project_name,
            audit=audit,
            progress_callback=_on_version_start,
        )

        # Mark the bar fully complete (the callback fires BEFORE each version,
        # so the last update leaves the bar at N-1; advance it to N once the
        # loop returns successfully).
        if task_id is not None:
            progress.update(task_id, completed=result.versions_replayed)

    plan.version_remap.update(result.version_remap)
    plan.item_id_remap.update(result.item_id_remap)


def _copy_test_suite_config(rest_client: OpikApi, action: CopyTestSuiteConfig) -> None:
    """Copy the source's latest suite-level evaluators + execution_policy.

    Reads the most recent dataset version from the source (versions are
    listed newest-first), then issues apply_dataset_item_changes with
    override=True against the target dataset id. ``override=True`` skips the
    base-version check, which is what we want for an initial config payload
    on a freshly-created target.

    No-op when the source has no version yet (suites without configured
    evaluators/policy don't generate an initial version, by design — see
    rest_operations.create_test_suite_dataset).
    """
    versions = rest_client.datasets.list_dataset_versions(
        id=action.source_dataset_id, page=1, size=1
    )
    if not versions.content:
        return
    latest = versions.content[0]

    request: Dict[str, Any] = {
        "change_description": "Migrated suite config from source",
    }
    if latest.evaluators:
        request["evaluators"] = _evaluators_payload(latest.evaluators)
    if latest.execution_policy is not None:
        request["execution_policy"] = _execution_policy_payload(latest.execution_policy)
    if "evaluators" not in request and "execution_policy" not in request:
        # Latest version had no suite-level config beyond items — nothing
        # to copy. The target was already created with type='evaluation_suite'
        # which is sufficient.
        return

    # Target dataset id resolved by name within the destination project.
    dest = rest_client.datasets.get_dataset_by_identifier(
        dataset_name=action.dest_name, project_name=action.dest_project_name
    )
    rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: rest_client.datasets.apply_dataset_item_changes(
            id=dest.id, request=request, override=True
        )
    )


def _cascade_experiments(
    client: opik.Opik,
    rest_client: OpikApi,
    action: CascadeExperiments,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Drive the experiment cascade, with a Rich Progress bar matching
    ``_replay_versions``.

    ``cascade_experiments`` is console-agnostic; the progress UI lives here
    so the algorithmic core stays testable without Rich in the loop.
    """
    with Progress(
        TextColumn("[bold blue]Cascading experiments"),
        BarColumn(),
        TaskProgressColumn(),
        TextColumn("{task.description}"),
        console=_console,
        transient=False,
    ) as progress:
        task_id: Optional[int] = None

        def _on_experiment_start(completed: int, total: int, label: str) -> None:
            nonlocal task_id
            description = (
                f"→ {action.dest_project_name} · {label} ({completed + 1}/{total})"
            )
            if task_id is None:
                task_id = progress.add_task(description, total=total)
            else:
                progress.update(task_id, completed=completed, description=description)

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id=action.source_dataset_id,
            target_dataset_name=action.dest_name,
            target_project_name=action.dest_project_name,
            version_remap=plan.version_remap,
            item_id_remap=plan.item_id_remap,
            audit=audit,
            progress_callback=_on_experiment_start,
        )

        if task_id is not None:
            progress.update(task_id, completed=result.experiments_migrated)

    plan.trace_id_remap.update(result.trace_id_remap)


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
            "dataset_type": action.type,
        }
    if isinstance(action, CopyCurrentItems):
        return {
            "type": "copy_dataset_items",
            "from_dataset": action.source_name_after_rename,
            "from_project": action.source_project_name,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
        }
    if isinstance(action, CopyTestSuiteConfig):
        return {
            "type": "copy_test_suite_config",
            "source_dataset_id": action.source_dataset_id,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
        }
    if isinstance(action, ReplayVersions):
        return {
            "type": "replay_versions",
            "from_dataset": action.source_name_after_rename,
            "from_project": action.source_project_name,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
            "is_test_suite": action.is_test_suite,
        }
    if isinstance(action, CascadeExperiments):
        return {
            "type": "cascade_experiments",
            "source_dataset_id": action.source_dataset_id,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
        }
    raise TypeError(f"Unknown migration action: {type(action).__name__}")
