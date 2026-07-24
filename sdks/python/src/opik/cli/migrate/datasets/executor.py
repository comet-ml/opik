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
from opik.rest_api.core.api_error import ApiError
from rich.console import Console
from rich.progress import BarColumn, Progress, TaskProgressColumn, TextColumn

from ..audit import AuditLog
from ..checkpoint import MigrationCheckpoint
from .._base import execute_plan_loop, record_planned_loop
from .experiments import cascade_experiments
from .optimizations import cascade_optimizations
from .planner import (
    CascadeExperiments,
    CascadeOptimizations,
    CreateDestination,
    DiscardStaleTemp,
    MigrationPlan,
    PromoteDestination,
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
    checkpoint: Optional[MigrationCheckpoint] = None,
) -> None:
    """Apply ``plan`` against ``client``, recording each action in ``audit``.

    Delegates the audit-bracketed loop to ``_base.execute_plan_loop`` and
    supplies the dataset-specific apply / details closures. The closure
    captures ``plan`` and ``audit`` so the ``ReplayVersions`` branch can
    stash version_remap / item_id_remap onto the plan and emit per-version
    audit sub-records.

    ``checkpoint`` (OPIK-7168), when supplied, is threaded into the experiment
    cascade for resume support; the other actions ignore it.
    """
    rest_client = client.rest_client

    def _apply(action: Any) -> None:
        _apply_action(
            client, rest_client, action, plan=plan, audit=audit, checkpoint=checkpoint
        )

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
    checkpoint: Optional[MigrationCheckpoint] = None,
) -> None:
    # Every REST call in the migrate path -- both writes and reads -- is
    # wrapped with the SDK's 429-aware retry helper so a transient rate
    # limit doesn't abort a half-finished migration. Reads are wrapped
    # too because aborting mid-cascade on a list_dataset_versions /
    # find_experiments 429 wastes the work done up to that point.
    if isinstance(action, DiscardStaleTemp):
        # A temp destination from a prior failed run — delete it so the
        # re-run's CreateDestination starts clean (discard-and-restart).
        # Best-effort: if it's already gone (deleted out-of-band between plan
        # and apply), that's the desired end state, so swallow the 404 and
        # continue rather than aborting an otherwise-healthy migration. Any
        # other API error still propagates so real failures aren't masked.
        try:
            rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                lambda: rest_client.datasets.delete_dataset(id=action.temp_id)
            )
        except ApiError as exc:
            if exc.status_code == 404:
                LOGGER.info(
                    "Stale temp dataset %s already deleted; continuing.",
                    action.temp_id,
                )
            else:
                raise
    elif isinstance(action, RenameSource):
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
    elif isinstance(action, PromoteDestination):
        # Resolve the temp destination by its (temp) name — it was created
        # at execute time, so its id wasn't known when the plan was built.
        dest = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: client.get_dataset(
                name=action.from_name, project_name=action.project_name
            )
        )
        # Pass tags as an EXPLICIT list (never None). The temp carries the
        # ``opik-migrate-temp`` marker from CreateDestination; the promote PUT
        # must overwrite tags with the source's originals to strip it. The BE
        # treats ``tags=None``/omitted as "leave tags unchanged" (verified on a
        # live backend), so a source with no tags would otherwise leave the
        # marker on the final dataset — pass ``[]`` to actually clear it.
        promote_tags = action.tags if action.tags is not None else []
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.datasets.update_dataset(
                id=dest.id,
                name=action.to_name,
                description=action.description,
                visibility=action.visibility,
                tags=promote_tags,
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
    elif isinstance(action, CascadeOptimizations):
        _cascade_optimizations(rest_client, action, plan=plan, audit=audit)
        # The dataset-level phases (create-temp/replay/optimizations) are all
        # done as of here -- CascadeOptimizations is always the last one before
        # CascadeExperiments. Mark it on the checkpoint NOW, before the cascade
        # boundary, so a crash between this point and the first experiment still
        # leaves ``dataset_phase_done=True``; the next run then resumes into the
        # cascade + pending handoff instead of restarting the copy. The source
        # is still under its original name and the destination under the temp
        # name at this point (the handoff runs only after the cascade).
        if (
            checkpoint is not None
            and not plan.is_resume
            and not checkpoint.dataset_phase_done
        ):
            checkpoint.mark_dataset_phase_done(
                source_dataset_id=action.source_dataset_id,
                source_name=plan.source_name,
                temp_dest_name=plan.temp_dest_name,
            )
            checkpoint.flush()
    elif isinstance(action, CascadeExperiments):
        _cascade_experiments(
            client, rest_client, action, plan=plan, audit=audit, checkpoint=checkpoint
        )
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
            source_name=action.source_name,
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


def _cascade_optimizations(
    rest_client: OpikApi,
    action: CascadeOptimizations,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
) -> None:
    """Recreate every source-dataset optimization under the destination
    project and populate ``plan.optimization_id_remap`` so the subsequent
    ``CascadeExperiments`` action can re-point each experiment's
    ``optimization_id`` FK at the new destination optimization id.

    Drives a Rich progress bar that ticks once per optimization. The
    algorithmic core in ``cascade_optimizations`` stays console-agnostic
    -- the bar is driven by the per-optimization callback. Production
    datasets carry tens to low-hundreds of optimizations at most, so a
    simple single-level bar is plenty (no nested per-optimization detail
    bar like the experiment cascade has).
    """
    with Progress(
        TextColumn("[bold blue]Cascading optimizations"),
        BarColumn(),
        TaskProgressColumn(),
        TextColumn("{task.description}"),
        console=_console,
        transient=False,
    ) as progress:
        task_id: Optional[int] = None

        def _on_optimization_start(completed: int, total: int, label: str) -> None:
            nonlocal task_id
            # ``label == "done"`` signals the final tick (completed=total).
            # Drop the "(N/total)" suffix in that frame so the bar doesn't
            # render "(total+1/total)" from the +1 offset that helps mid-
            # loop ticks read as "currently processing the (Nth+1) item".
            is_done = label == "done"
            if is_done:
                description = f"→ {action.dest_project_name} · done"
            else:
                description = (
                    f"→ {action.dest_project_name} · {label} ({completed + 1}/{total})"
                )
            # ``total`` might be 0 on a zero-optimization dataset; clamp to
            # >= 1 so the Rich bar doesn't render as divide-by-zero NaN%.
            # The same value is used for the bar's ``completed`` on the
            # "done" tick so the zero-optimization path still renders as
            # 100% (otherwise the bar would lazily add the task at total=1
            # and never advance, leaving an already-finished migration
            # stuck at 0%).
            bar_total = max(total, 1)
            if task_id is None:
                task_id = progress.add_task(description, total=bar_total)
            if is_done:
                progress.update(task_id, completed=bar_total, description=description)
            else:
                progress.update(task_id, completed=completed, description=description)

        result = cascade_optimizations(
            rest_client,
            source_dataset_id=action.source_dataset_id,
            target_dataset_name=action.dest_name,
            target_project_name=action.dest_project_name,
            audit=audit,
            progress_callback=_on_optimization_start,
        )

    plan.optimization_id_remap.update(result.id_remap)


def _cascade_experiments(
    client: opik.Opik,
    rest_client: OpikApi,
    action: CascadeExperiments,
    *,
    plan: MigrationPlan,
    audit: AuditLog,
    checkpoint: Optional[MigrationCheckpoint] = None,
) -> None:
    """Drive the experiment cascade with nested Rich progress bars.

    Outer bar tracks experiments (one tick per experiment); inner bar
    tracks the per-experiment work (one tick per read/write/flush so the
    user sees motion even on a single long-running experiment with
    hundreds of traces). ``cascade_experiments`` is console-agnostic; the
    progress UI lives here so the algorithmic core stays testable without
    Rich in the loop.

    When ``checkpoint`` is supplied, it is threaded into the cascade for
    resume support and the outer bar is seeded with the first callback's
    ``completed`` value so a resumed run renders at the right percentage
    (OPIK-7168) rather than starting from 0. The dataset phase is marked done
    by ``_apply_action`` right after ``CascadeOptimizations`` (before this
    cascade boundary), so it isn't touched here.
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
                # Seed ``completed`` so a resumed run's bar opens at the right
                # percentage. On a fresh run ``completed`` is 0, so this is a
                # no-op relative to the prior behavior.
                outer_task_id = progress.add_task(
                    description, total=total, completed=completed
                )
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

        # The destination dataset id is only needed for resume cleanup (to find
        # a possibly-orphaned destination experiment by name); resolve it once
        # here so the cascade doesn't have to. Skipped entirely without a
        # checkpoint so a plain run makes no extra call.
        target_dataset_id: Optional[str] = None
        if checkpoint is not None:
            dest = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
                lambda: client.get_dataset(
                    name=action.dest_name, project_name=action.dest_project_name
                )
            )
            target_dataset_id = dest.id

        result = cascade_experiments(
            client,
            rest_client,
            source_dataset_id=action.source_dataset_id,
            target_dataset_name=action.dest_name,
            target_project_name=action.dest_project_name,
            target_dataset_id=target_dataset_id,
            version_remap=plan.version_remap,
            item_id_remap=plan.item_id_remap,
            optimization_id_remap=plan.optimization_id_remap,
            audit=audit,
            checkpoint=checkpoint,
            progress_callback=_on_experiment_start,
            inner_progress_callback=_on_inner_step,
        )
        # No post-cascade ``progress.update`` here: the callback's final
        # ``progress_callback(total, total, "done")`` tick already advanced
        # the bar to ``completed=total``. Overwriting with
        # ``result.experiments_migrated`` would drop the bar below 100% when
        # any experiment was skipped (e.g. ``recreate_experiment`` returned
        # False because all items missed the trace_id remap).

    # Surface cascade counters on a separate audit record (additive --
    # the existing ``cascade_experiments`` action record is unchanged
    # from Slice 3, so callers reading the action's pre/post state keep
    # working). The action-loop framework calls ``_action_details``
    # BEFORE ``apply_fn`` so the per-action ``ok`` record can't carry
    # post-apply counters; a summary record after the cascade is the
    # cleanest additive surface.
    # Summary status flips to "failed" when any skip counter is non-zero so
    # a programmatic consumer doing ``jq '.actions[] | select(.status ==
    # "failed")'`` picks it up. Per-(experiment, reason) ``skip`` records
    # emitted by the cascade itself carry ``status="skipped"`` and the
    # affected source ids; this summary aggregates the totals (OPIK-6599).
    has_skips = (
        result.experiments_skipped
        + result.items_skipped_missing_trace
        + result.items_skipped_missing_item
    ) > 0
    audit.record(
        type="cascade_experiments_summary",
        status="failed" if has_skips else "ok",
        details={
            "source_dataset_id": action.source_dataset_id,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
            "experiments_migrated": result.experiments_migrated,
            "experiments_skipped": result.experiments_skipped,
            "traces_migrated": result.traces_migrated,
            "spans_migrated": result.spans_migrated,
            "trace_comments_migrated": result.trace_comments_migrated,
            "span_comments_migrated": result.span_comments_migrated,
            "items_skipped_missing_trace": result.items_skipped_missing_trace,
            "items_skipped_missing_item": result.items_skipped_missing_item,
        },
    )

    plan.trace_id_remap.update(result.trace_id_remap)


def _action_details(action: object) -> Dict[str, Any]:
    if isinstance(action, DiscardStaleTemp):
        return {
            "type": "discard_stale_temp",
            "entity": "dataset",
            "id": action.temp_id,
            "name": action.temp_name,
        }
    if isinstance(action, RenameSource):
        return {
            "type": "rename_source",
            "entity": "dataset",
            "id": action.source_id,
            "from": action.from_name,
            "to": action.to_name,
        }
    if isinstance(action, PromoteDestination):
        return {
            "type": "promote_destination",
            "entity": "dataset",
            "from": action.from_name,
            "to": action.to_name,
            "project": action.project_name,
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
            "from_dataset": action.source_name,
            "from_project": action.source_project_name,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
            "is_test_suite": action.is_test_suite,
        }
    if isinstance(action, CascadeOptimizations):
        return {
            "type": "cascade_optimizations",
            "source_dataset_id": action.source_dataset_id,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
        }
    if isinstance(action, CascadeExperiments):
        return {
            "type": "cascade_experiments",
            "source_dataset_id": action.source_dataset_id,
            "to_dataset": action.dest_name,
            "to_project": action.dest_project_name,
        }
    raise TypeError(f"Unknown migration action: {type(action).__name__}")
