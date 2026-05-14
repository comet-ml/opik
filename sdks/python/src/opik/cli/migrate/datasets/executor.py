"""Executor for the dataset ``MigrationPlan``.

The audit-bracketed for-loop and the dry-run "record planned" loop are
generic across entity types and live in ``cli/migrate/_base.py``. This
module owns dataset-specific action dispatch (``_apply_action``) and the
audit-log payload shape per action type (``_action_details``).
"""

from __future__ import annotations

import logging
import time
from typing import Any, Dict, Optional

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
    CreateDestination,
    MigrationPlan,
    RenameSource,
    ReplayVersions,
)
from .version_replay import replay_all_versions

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
    # Every REST call in the migrate path -- both writes and reads -- is
    # wrapped with the SDK's 429-aware retry helper so a transient rate
    # limit doesn't abort a half-finished migration. Reads are wrapped
    # too because aborting mid-cascade on a list_dataset_versions /
    # find_experiments 429 wastes the work done up to that point.
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
    elif isinstance(action, ReplayVersions):
        _replay_versions(client, rest_client, action, plan=plan, audit=audit)
    elif isinstance(action, CascadeExperiments):
        _cascade_experiments(client, rest_client, action, plan=plan, audit=audit)
    else:
        raise TypeError(f"Unknown migration action: {type(action).__name__}")


def _replay_versions(
    client: opik.Opik,
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
    # ``client.get_dataset`` wraps ``get_dataset_by_identifier`` and
    # returns the SDK Dataset object (whose ``.id`` we use downstream).
    dest = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: client.get_dataset(
            name=action.dest_name, project_name=action.dest_project_name
        )
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


def _cascade_experiments(
    client: opik.Opik,
    rest_client: OpikApi,
    action: CascadeExperiments,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Drive the experiment cascade with nested Rich progress bars.

    Outer bar tracks experiments (one tick per experiment); inner bar
    tracks the per-experiment work (one tick per read/write/flush so the
    user sees motion even on a single long-running experiment with
    hundreds of traces). ``cascade_experiments`` is console-agnostic; the
    progress UI lives here so the algorithmic core stays testable without
    Rich in the loop.
    """
    with Progress(
        TextColumn("[bold blue]Cascading experiments"),
        BarColumn(),
        TaskProgressColumn(),
        TextColumn("{task.description}"),
        console=_console,
        transient=False,
    ) as progress:
        outer_task_id: Optional[int] = None
        # The inner task is created lazily on the first inner tick and
        # reset (``completed=0``, new ``total``) at every outer tick so
        # its 0-100% sweep represents ONE experiment's work. ``Rich`` allows
        # changing a task's total mid-flight via ``progress.update``.
        inner_task_id: Optional[int] = None
        # Wall-clock anchor for the next milestone's "(took N.Ns)" tag.
        # Reset at the start of each experiment so per-phase timings are
        # measured relative to the previous milestone of THIS experiment,
        # not the previous experiment's last milestone.
        last_milestone_at: float = time.monotonic()

        def _on_experiment_start(completed: int, total: int, label: str) -> None:
            nonlocal outer_task_id, inner_task_id, last_milestone_at
            # ``label == "done"`` signals the cascade's final tick (completed=total).
            # In that frame we drop the ``(N/total)`` suffix so the bar doesn't
            # render ``(total+1/total)`` from the +1 offset that helps mid-loop
            # ticks read as "currently processing the (Nth+1) experiment".
            if label == "done":
                description = f"→ {action.dest_project_name} · done"
            else:
                description = (
                    f"→ {action.dest_project_name} · {label} ({completed + 1}/{total})"
                )
            if outer_task_id is None:
                outer_task_id = progress.add_task(description, total=total)
            else:
                progress.update(
                    outer_task_id, completed=completed, description=description
                )
            # Park the inner bar between experiments: snap to 0/0 with a
            # neutral label so it doesn't show a stale per-experiment
            # progress from the experiment that just finished while the
            # next one's items are being read.
            if inner_task_id is not None and label != "done":
                progress.update(
                    inner_task_id, completed=0, total=1, description="starting…"
                )
            # Reset the milestone clock so the first milestone of this
            # experiment is timed from its actual start, not from the
            # previous experiment's recreate.
            last_milestone_at = time.monotonic()

        def _on_inner_step(
            inner_completed: int, inner_total: int, inner_label: str
        ) -> None:
            nonlocal inner_task_id, last_milestone_at
            # First inner tick of the first experiment: lazily add the
            # second task so the outer bar isn't visually orphaned when an
            # experiment has zero traces and no inner ticks would fire.
            description = f"   ↳ {inner_label}"
            if inner_task_id is None:
                inner_task_id = progress.add_task(description, total=inner_total)
            progress.update(
                inner_task_id,
                completed=inner_completed,
                total=inner_total,
                description=description,
            )
            # Print milestone lines to scrollback (the inner Rich bar
            # overwrites itself, so per-phase history is otherwise lost).
            # Per-trace ticks -- "trace 47/1000" / "spans for trace
            # 47/1000" -- only update the bar; they fire thousands of
            # times per experiment and would flood the terminal. Every
            # other label is a milestone (bulk reads, flushes, log-
            # scores, log-assertions, recreate/skipped) and gets one
            # persistent line with the wall-clock delta from the
            # previous milestone of THIS experiment.
            if not (
                inner_label.startswith("trace ")
                or inner_label.startswith("spans for trace ")
            ):
                now = time.monotonic()
                _console.print(
                    f"   [green]✓[/green] {inner_label} "
                    f"[dim](took {now - last_milestone_at:.1f}s)[/dim]"
                )
                last_milestone_at = now

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
            inner_progress_callback=_on_inner_step,
        )
        # No post-cascade ``progress.update`` here: the callback's final
        # ``progress_callback(total, total, "done")`` tick already advanced
        # the bar to ``completed=total``. Overwriting with
        # ``result.experiments_migrated`` would drop the bar below 100% when
        # any experiment was skipped (e.g. ``recreate_experiment`` returned
        # False because all items missed the trace_id remap).

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
