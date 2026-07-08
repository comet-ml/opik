"""Action planning for ``opik migrate dataset``.

The planner builds a ``MigrationPlan`` of typed action records. The executor
applies them in list order; this separation is what lets ``--dry-run``
reuse the same logic with no side effects.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Dict, List, Optional

import opik

from opik.api_objects import rest_helpers

from .._base import BaseMigrationPlan
from ..errors import ConflictError, MigrationError, UnsupportedDatasetTypeError
from .resolver import (
    ResolvedDataset,
    ensure_destination_project_exists,
    find_dataset_in_workspace,
    name_taken_in_workspace,
    resolve_source,
)
from .resume import reconstruct_remaps

if TYPE_CHECKING:
    from ..checkpoint import MigrationCheckpoint

# The migrate command supports plain datasets and test suites. Test suites
# (type 'evaluation_suite') carry suite-level evaluators + execution_policy
# as versioned config; ``ReplayVersions`` walks every source version and
# reproduces the suite-config history on the target natively.
SUPPORTED_DATASET_TYPES = {None, "dataset", "evaluation_suite"}
TEST_SUITE_TYPE = "evaluation_suite"

# Hardcoded suffix appended to the source dataset's name during the
# rename-on-success step (source -> ``<name>_v1``). Not exposed as a flag
# today; if a future need surfaces, the planner is the right place to add a
# parameter.
SOURCE_SUFFIX = "_v1"

# Suffix marking a destination as an in-progress (incomplete) migration
# target. The destination is created and fully populated under
# ``<name>__migrating`` while the source keeps its original name; only once
# the copy is proven complete is it promoted to ``<name>`` (OPIK-7162). A
# deterministic suffix (not a per-run id) is what lets a re-run reliably
# find and discard a stale temp left by a prior failed run — no orphans, no
# manual cleanup. The name isn't assumed unguessable: the planner's
# pre-flight aborts if a *real* dataset already holds this name.
TEMP_MIGRATION_SUFFIX = "__migrating"


@dataclass(frozen=True)
class DiscardStaleTemp:
    """Delete a leftover temp destination from a prior failed run.

    Only emitted when the pre-flight finds an existing ``<name>__migrating``
    dataset (a stale artifact of an interrupted earlier migration). Deleting
    it up-front gives ``CreateDestination`` a clean slate, which is what
    makes a re-run safe and idempotent (discard-and-restart, not resume).
    Resuming from the temp's partial progress instead of discarding it is
    the follow-up work tracked in OPIK_7168.
    """

    temp_id: str
    temp_name: str


@dataclass(frozen=True)
class CreateDestination:
    name: str
    project_name: str
    description: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]
    # 'dataset' (default) or 'evaluation_suite'. Only forwarded when the
    # source was a test suite — passing the same type is what makes the
    # target accept suite-level evaluators + execution_policy later.
    type: Optional[str]


@dataclass(frozen=True)
class ReplayVersions:
    """Replay every source dataset version onto the destination.

    Iterates source versions chronologically, computing the (adds,
    modifications, deletions) delta for each and applying it via
    ``apply_dataset_item_changes`` so the target accumulates one new
    version per source version with the same semantic content.

    The executor populates ``MigrationPlan.version_remap`` and
    ``MigrationPlan.item_id_remap`` after this action runs;
    ``CascadeExperiments`` reads those maps to remap experiment FK
    references.

    ``source_name`` is the source's *original* name: under the OPIK-7162
    ordering the source is never renamed until the very end, so item reads
    stream from it directly. ``dest_name`` is the *temp* name — the copy
    phase writes into ``<name>__migrating`` and the destination is promoted
    to the original name only after everything succeeds.
    """

    source_dataset_id: str
    source_name: str
    source_project_name: Optional[str]
    dest_name: str
    dest_project_name: str
    is_test_suite: bool


@dataclass(frozen=True)
class RenameSource:
    source_id: str
    from_name: str
    to_name: str
    # Re-passed verbatim on the rename PUT so the BE doesn't wipe these
    # (description in particular gets nulled when omitted from update_dataset;
    # tags/visibility appear to survive but we re-pass them defensively).
    description: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]


@dataclass(frozen=True)
class PromoteDestination:
    """Rename the fully-migrated temp destination to the original name.

    Runs last, AFTER ``RenameSource`` has freed the original name by moving
    the source to ``<name>_v1``. The two renames are ordered
    source-away-then-destination-in so ``<name>`` is never held by two
    datasets at once (respects the BE ``UNIQUE(workspace_id, name)``).
    Metadata is re-passed on the PUT for the same reason ``RenameSource``
    does it — ``update_dataset`` nulls description when omitted.

    The temp destination is created at execute time, so its id isn't known
    at plan time; the executor resolves it by ``from_name`` (the temp name)
    just before the rename PUT.
    """

    from_name: str
    to_name: str
    project_name: str
    description: Optional[str]
    visibility: Optional[str]
    tags: Optional[List[str]]


@dataclass(frozen=True)
class CascadeOptimizations:
    """Cascade every optimization referencing the source dataset.

    Umbrella action ordered AFTER ``ReplayVersions`` and BEFORE
    ``CascadeExperiments`` so the destination optimization ids are known
    by the time experiments are recreated and can FK to them via
    ``plan.optimization_id_remap``.

    Source-side reads use ``find_optimizations(dataset_id=...)`` which is
    project-agnostic: a single query returns every optimization tied to
    the source dataset regardless of which project it lives in (mirrors
    ``find_experiments(dataset_id=...)`` in Slice 3). Destination-side
    each optimization is recreated under ``dest_project_name`` with a
    fresh client-side UUID.

    Empty optimizations (zero constituent experiments) are migrated as
    well — they're still user-visible rows in the Optimization Studio UI.
    """

    source_dataset_id: str
    dest_name: str
    dest_project_name: str


@dataclass(frozen=True)
class CascadeExperiments:
    """Cascade every experiment referencing the source dataset.

    Umbrella action — fires once per source dataset and internally loops
    over every experiment that referenced it. Reuses ``ReplayVersions``'s
    ``version_remap`` and ``item_id_remap`` (already populated on the plan)
    to rewrite each experiment's ``dataset_version_id`` /
    ``dataset_item_id`` FK fields, reads ``CascadeOptimizations``'s
    ``optimization_id_remap`` to re-point each experiment's
    ``optimization_id``, and builds its own ``trace_id`` remap as traces
    ride along with the experiments.

    Per-experiment failures stop the cascade (the audit log captures
    partial progress on the ``failed`` entry). Per-item missing-trace /
    missing-item conditions are tallied as skip counters rather than
    failures.
    """

    source_dataset_id: str
    dest_name: str
    dest_project_name: str
    # NB: the cascade does NOT carry a dataset-level source project name.
    # Source-side reads (``find_dataset_items_with_experiment_items``,
    # ``get_spans_by_project``) scope by ``project_id`` on a PER-EXPERIMENT
    # basis -- ``source_experiment.project_id`` -- because experiments
    # are always project-scoped (unlike datasets, which can be workspace-
    # scoped) and cross-project experiments referencing the same source
    # dataset legitimately live in different projects.


@dataclass(kw_only=True)
class MigrationPlan(BaseMigrationPlan):
    """Dataset-specific migration plan.

    Inherits the shared ``source`` / ``actions`` shape from
    ``BaseMigrationPlan`` and narrows ``source`` to ``ResolvedDataset``.
    The action list contains dataset-specific records (``CreateDestination``,
    ``ReplayVersions``, ``CascadeExperiments``, ``RenameSource``,
    ``PromoteDestination``).

    The executor sets ``version_remap`` / ``item_id_remap`` on the plan
    after a successful ``ReplayVersions`` run; ``CascadeExperiments``
    reads them to remap experiment foreign-key references and writes
    ``trace_id_remap`` for downstream consumers (e.g. the optimization
    cascade in OPIK-6417).
    """

    source: ResolvedDataset
    target_name: str
    to_project: str
    # The source dataset's ORIGINAL name (it isn't renamed until the run
    # succeeds) and the temp destination name (``<name>__migrating``). Captured
    # so the executor can record them on the checkpoint when the dataset phase
    # completes, and so a resumed run can re-resolve both datasets.
    source_name: str = ""
    temp_dest_name: str = ""
    # True when this plan was built for a RESUME (dataset phase already done on a
    # prior run): replay is omitted and its remaps are pre-populated by
    # ``reconstruct_remaps``, so the executor must NOT re-mark the dataset phase
    # or re-run replay. The still-pending cascade + handoff actions are present.
    is_resume: bool = False
    version_remap: Dict[str, str] = field(default_factory=dict)
    item_id_remap: Dict[str, str] = field(default_factory=dict)
    trace_id_remap: Dict[str, str] = field(default_factory=dict)
    optimization_id_remap: Dict[str, str] = field(default_factory=dict)


def build_dataset_plan(
    client: opik.Opik,
    name: str,
    to_project: str,
    from_project: Optional[str] = None,
    exclude_experiments: bool = False,
    resume_checkpoint: Optional["MigrationCheckpoint"] = None,
) -> MigrationPlan:
    """Build the ordered action list for migrating one dataset.

    Ordering invariant (OPIK-7162): the source keeps its original name for
    the entire copy; the destination is built under a temp name
    (``<name>__migrating``) and only promoted to the original name once the
    copy is proven complete. The two closing renames are ordered
    source-away-then-destination-in so the workspace-unique-name constraint
    never trips and — critically — a failure at any earlier step leaves the
    source name untouched and only a discardable temp behind.

    When ``resume_checkpoint`` has ``dataset_phase_done`` set (OPIK-7168), the
    create-temp/replay/optimizations phases already completed on a prior run,
    so this returns a RESUME plan that reuses the temp destination and finishes
    the still-pending cascade + handoff (``CascadeExperiments`` →
    ``RenameSource`` → ``PromoteDestination``). Replay is omitted — re-running
    it would duplicate every destination version — and its remaps are rebuilt
    read-only from the temp destination via ``reconstruct_remaps``.

    ``from_project`` is an optional source-scope hint (perf + clearer
    error message); ``None`` does a workspace-wide source lookup.

    ``exclude_experiments`` (OPIK-7161) drops the experiment stage. An
    Optimization is a container defined by its constituent experiments, so
    skipping experiments also skips the optimization cascade — otherwise the
    destination would carry empty optimization shells FK-ing experiments that
    were never migrated. The dataset + full version history still migrate.

    The plan emits, in order:

      0. ``DiscardStaleTemp`` — only when a temp destination from a prior
         failed run already exists; deletes it so the re-run starts clean
         (discard-and-restart; safe idempotent re-run).
      1. ``CreateDestination`` — creates the target under the *temp* name.
      2. ``ReplayVersions`` — replays every source version onto the temp
         destination, populating ``plan.version_remap`` /
         ``plan.item_id_remap``.
      3. ``CascadeOptimizations`` — recreates every optimization referencing
         the source dataset under the destination project, populating
         ``plan.optimization_id_remap``. Omitted when
         ``exclude_experiments`` is set.
      4. ``CascadeExperiments`` — recreates every experiment referencing the
         source dataset (traces + spans ride along), re-pointing each
         ``optimization_id`` via ``plan.optimization_id_remap``. Omitted when
         ``exclude_experiments`` is set.
      5. ``RenameSource`` — moves the source to ``<name>_v1``, freeing the
         original name. Runs only after the copy fully succeeds.
      6. ``PromoteDestination`` — renames the temp destination to the
         original name.
    """
    if resume_checkpoint is not None and resume_checkpoint.dataset_phase_done:
        return _build_resume_plan(client, to_project, resume_checkpoint)

    # Fail fast if --to-project doesn't exist. Catches typos before any
    # rename/create/copy work, and prevents auto-creating a stray project.
    ensure_destination_project_exists(client, to_project)

    source = resolve_source(client, name, from_project)

    if source.type not in SUPPORTED_DATASET_TYPES:
        raise UnsupportedDatasetTypeError(
            f"Dataset '{source.name}' has unsupported type '{source.type}'. "
            f"`opik migrate` supports {sorted(t for t in SUPPORTED_DATASET_TYPES if t)}."
        )

    is_test_suite = source.type == TEST_SUITE_TYPE
    name_after_rename = f"{source.name}{SOURCE_SUFFIX}"
    temp_name = f"{source.name}{TEMP_MIGRATION_SUFFIX}"

    # Dataset names are workspace-unique, so the eventual source-rename
    # target must be free workspace-wide (excluding the source itself,
    # which is about to be renamed). Checked up-front so a doomed run fails
    # before doing any copy work rather than at the final rename PUT.
    collision = name_taken_in_workspace(
        client, name_after_rename, ignore_dataset_id=source.id
    )
    if collision:
        raise ConflictError(
            f"Cannot rename source to '{name_after_rename}' — that name is "
            f"already used by a dataset in {collision}. "
            "Rename or delete the conflicting dataset and re-run."
        )

    # The temp name must also be free workspace-wide. An existing dataset
    # under it is either (a) a stale temp from a prior failed migration of
    # this same source — safe to discard and restart — or (b) a real,
    # unrelated user dataset that happens to share the name, which we must
    # not touch. We distinguish the two by the deterministic naming
    # contract: a ``<source>__migrating`` dataset is only ever created by
    # this tool, so treat it as reclaimable.
    stale_temp = find_dataset_in_workspace(client, temp_name)

    plan = MigrationPlan(
        source=source,
        target_name=source.name,
        to_project=to_project,
        source_name=source.name,
        temp_dest_name=temp_name,
    )

    # Discard-and-restart: a stale temp is deleted and the copy re-runs from
    # scratch. This branch is only reached on the NORMAL path — a resumable
    # checkpoint (``dataset_phase_done``) short-circuits to ``_build_resume_plan``
    # above and REUSES the temp instead of discarding it. So we only get here
    # when there's no resumable progress (a fresh run, or a crash mid-replay
    # before the dataset phase was marked done), where discarding the partial
    # temp and restarting is the correct, idempotent behavior (OPIK-7162 /
    # OPIK-7168).
    if stale_temp is not None:
        plan.actions.append(
            DiscardStaleTemp(temp_id=stale_temp.id, temp_name=temp_name)
        )

    plan.actions.append(
        CreateDestination(
            name=temp_name,
            project_name=to_project,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
            # Only forward the type when it differs from the default —
            # creating a 'dataset' explicitly is harmless but creating an
            # 'evaluation_suite' is what makes the target accept suite config.
            type=source.type if is_test_suite else None,
        )
    )

    plan.actions.append(
        ReplayVersions(
            source_dataset_id=source.id,
            source_name=source.name,
            source_project_name=source.project_name,
            dest_name=temp_name,
            dest_project_name=to_project,
            is_test_suite=is_test_suite,
        )
    )

    # OPIK-7161: --exclude-experiments short-circuits before both cascades.
    # No experiment discovery runs, dodging the OPIK-7152 large-dataset
    # cascade failure mode entirely. Optimizations are gated by the same
    # flag because they only make sense as containers for the experiments
    # being skipped.
    if not exclude_experiments:
        # CascadeOptimizations runs AFTER ReplayVersions (the destination
        # dataset exists by now) and BEFORE CascadeExperiments so the
        # experiment-write phase can FK to the new destination optimization
        # ids via plan.optimization_id_remap. It's a no-op when zero
        # optimizations reference the source dataset.
        plan.actions.append(
            CascadeOptimizations(
                source_dataset_id=source.id,
                dest_name=temp_name,
                dest_project_name=to_project,
            )
        )

        # Cascade experiments must run AFTER ReplayVersions and
        # CascadeOptimizations so plan.version_remap / plan.item_id_remap /
        # plan.optimization_id_remap are populated when the cascade reads
        # them.
        plan.actions.append(
            CascadeExperiments(
                source_dataset_id=source.id,
                dest_name=temp_name,
                dest_project_name=to_project,
            )
        )

    # Name handoff — only reached when every copy action above succeeds.
    # Source away first (frees the original name), destination in second
    # (claims it). Never overlapping, so UNIQUE(workspace_id, name) holds.
    plan.actions.append(
        RenameSource(
            source_id=source.id,
            from_name=source.name,
            to_name=name_after_rename,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
        )
    )

    plan.actions.append(
        PromoteDestination(
            from_name=temp_name,
            to_name=source.name,
            project_name=to_project,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
        )
    )

    return plan


def _build_resume_plan(
    client: opik.Opik,
    to_project: str,
    checkpoint: "MigrationCheckpoint",
) -> MigrationPlan:
    """Build a resume plan that finishes a run interrupted after the dataset
    phase (create-temp/replay/optimizations) but before completion.

    Under the OPIK-7162 temp-dest ordering, when the dataset phase is done:

    * The source still carries its ORIGINAL name (it isn't renamed until the
      whole run succeeds), so it resolves by ``checkpoint.dataset`` — the
      user-supplied name. We re-resolve it live to recover its metadata for the
      pending rename PUT. Its id must match the checkpoint's ``source_dataset_id``.
    * The destination is still under the TEMP name (``checkpoint.temp_dest_name``);
      the promote-to-original step is part of the pending handoff.
    * ``version_remap`` / ``item_id_remap`` / ``optimization_id_remap`` are
      rebuilt read-only from the temp destination (``reconstruct_remaps``);
      nothing is re-written, so no versions are duplicated.

    The returned plan contains the still-pending tail:
    ``CascadeExperiments`` → ``RenameSource`` → ``PromoteDestination``. The
    checkpoint drives per-experiment skip/resume inside the cascade; the two
    renames complete the handoff the interrupted run never reached.
    """
    if (
        not checkpoint.source_dataset_id
        or not checkpoint.source_name
        or not checkpoint.temp_dest_name
    ):
        raise MigrationError(
            "Cannot resume: the checkpoint is missing the source id, source "
            "name, or temp destination name. Delete the "
            f"'{checkpoint.temp_dest_name or '<name>__migrating'}' dataset (if "
            "any) and re-run the migration from scratch."
        )

    ensure_destination_project_exists(client, to_project)

    # The source is unrenamed, so its original name still resolves to it.
    # Re-resolve live to recover description/visibility/tags for the pending
    # rename PUT, and to verify the id still matches the checkpoint.
    source = resolve_source(client, checkpoint.dataset, None)
    if source.id != checkpoint.source_dataset_id:
        raise MigrationError(
            f"Cannot resume: '{checkpoint.dataset}' now resolves to a different "
            "dataset than the interrupted run's source. Delete the "
            f"'{checkpoint.temp_dest_name}' dataset and re-run from scratch."
        )

    name_after_rename = f"{source.name}{SOURCE_SUFFIX}"

    # Destination = the TEMP-named dataset the first run created under
    # --to-project (promote hasn't run yet).
    dest = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: client.get_dataset(
            name=checkpoint.temp_dest_name, project_name=to_project
        )
    )

    remaps = reconstruct_remaps(
        client.rest_client,
        source_dataset_id=checkpoint.source_dataset_id,
        source_name=checkpoint.source_name,
        source_project_name=source.project_name,
        dest_dataset_id=dest.id,
        dest_name=checkpoint.temp_dest_name,
        dest_project_name=to_project,
    )

    plan = MigrationPlan(
        source=source,
        target_name=source.name,
        to_project=to_project,
        source_name=source.name,
        temp_dest_name=checkpoint.temp_dest_name,
        is_resume=True,
    )
    plan.version_remap.update(remaps.version_remap)
    plan.item_id_remap.update(remaps.item_id_remap)
    plan.optimization_id_remap.update(remaps.optimization_id_remap)

    plan.actions.append(
        CascadeExperiments(
            source_dataset_id=checkpoint.source_dataset_id,
            dest_name=checkpoint.temp_dest_name,
            dest_project_name=to_project,
        )
    )
    # Finish the handoff the interrupted run never reached: source away first,
    # then promote the temp destination into the original name.
    plan.actions.append(
        RenameSource(
            source_id=source.id,
            from_name=source.name,
            to_name=name_after_rename,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
        )
    )
    plan.actions.append(
        PromoteDestination(
            from_name=checkpoint.temp_dest_name,
            to_name=source.name,
            project_name=to_project,
            description=source.description,
            visibility=source.visibility,
            tags=source.tags,
        )
    )
    return plan
