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

# Hardcoded suffix appended to the source dataset's name during the rename
# step. Not exposed as a flag today; if a future need surfaces, the planner
# is the right place to add a parameter.
SOURCE_SUFFIX = "_v1"


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
    """

    source_dataset_id: str
    source_name_after_rename: str
    source_project_name: Optional[str]
    dest_name: str
    dest_project_name: str
    is_test_suite: bool


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
    The action list contains dataset-specific records (``RenameSource``,
    ``CreateDestination``, ``ReplayVersions``, ``CascadeExperiments``).

    The executor sets ``version_remap`` / ``item_id_remap`` on the plan
    after a successful ``ReplayVersions`` run; ``CascadeExperiments``
    reads them to remap experiment foreign-key references and writes
    ``trace_id_remap`` for downstream consumers (e.g. the optimization
    cascade in OPIK-6417).
    """

    source: ResolvedDataset
    target_name: str
    to_project: str
    # The source dataset's post-rename name (``<name>_v1``). Captured so the
    # executor can record it on the checkpoint when the dataset phase completes,
    # and so a resumed run can re-resolve the source by it.
    source_name_after_rename: str = ""
    # True when this plan was built for a RESUME (dataset phase already done on a
    # prior run): it contains only the ``CascadeExperiments`` action and its
    # remaps are pre-populated by ``reconstruct_remaps``, so the executor must
    # NOT re-mark the dataset phase or re-run replay.
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

    When ``resume_checkpoint`` has ``dataset_phase_done`` set (OPIK-7168), the
    dataset-level phases (rename/create/replay/optimizations) already completed
    on a prior run, so this returns a RESUME plan: a single
    ``CascadeExperiments`` action whose remaps are rebuilt read-only from the
    already-migrated destination via ``reconstruct_remaps``. The rename/create/
    replay actions are deliberately omitted -- re-running them would collide on
    the rename and duplicate every destination version.

    Ordering invariant: the source rename always precedes the destination
    create, so the workspace-unique-name constraint never trips. The target
    keeps the source's original name.

    ``from_project`` is an optional source-scope hint (perf + clearer
    error message); ``None`` does a workspace-wide source lookup.

    ``exclude_experiments`` (OPIK-7161) drops the experiment stage. An
    Optimization is a container defined by its constituent experiments, so
    skipping experiments also skips the optimization cascade — otherwise the
    destination would carry empty optimization shells FK-ing experiments that
    were never migrated. The dataset + full version history still migrate.

    The plan emits, in order:

      1. ``RenameSource`` — frees the source's name for the destination.
      2. ``CreateDestination`` — creates the target dataset under ``to_project``.
      3. ``ReplayVersions`` — replays every source dataset version onto
         the target, populating ``plan.version_remap`` and
         ``plan.item_id_remap``.
      4. ``CascadeOptimizations`` — recreates every optimization
         referencing the source dataset under the destination project,
         populating ``plan.optimization_id_remap`` so the next action can
         re-point experiment FK references. Omitted when
         ``exclude_experiments`` is set.
      5. ``CascadeExperiments`` — recreates every experiment referencing
         the source dataset under the destination project, with traces +
         spans riding along, and re-points each experiment's
         ``optimization_id`` via ``plan.optimization_id_remap``. Omitted
         when ``exclude_experiments`` is set.
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

    # Dataset names are workspace-unique, so the rename target must be free
    # workspace-wide (excluding the source dataset itself, which is about
    # to be renamed). Without this check the rename PUT would 409 mid-flight.
    collision = name_taken_in_workspace(
        client, name_after_rename, ignore_dataset_id=source.id
    )
    if collision:
        raise ConflictError(
            f"Cannot rename source to '{name_after_rename}' — that name is "
            f"already used by a dataset in {collision}. "
            "Rename or delete the conflicting dataset and re-run."
        )

    plan = MigrationPlan(
        source=source,
        target_name=source.name,
        to_project=to_project,
        source_name_after_rename=name_after_rename,
    )

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
        CreateDestination(
            name=source.name,
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
            source_name_after_rename=name_after_rename,
            source_project_name=source.project_name,
            dest_name=source.name,
            dest_project_name=to_project,
            is_test_suite=is_test_suite,
        )
    )

    # OPIK-7161: --exclude-experiments short-circuits before both cascades.
    # The plan ends after ReplayVersions, so no experiment discovery ever
    # runs and the OPIK-7152 large-dataset cascade failure mode is dodged
    # entirely. Optimizations are gated by the same flag because they only
    # make sense as containers for the experiments being skipped.
    if not exclude_experiments:
        # CascadeOptimizations runs AFTER ReplayVersions (the destination
        # dataset exists by now) and BEFORE CascadeExperiments so the
        # experiment-write phase can FK to the new destination optimization
        # ids via plan.optimization_id_remap. It's a no-op when zero
        # optimizations reference the source dataset.
        plan.actions.append(
            CascadeOptimizations(
                source_dataset_id=source.id,
                dest_name=source.name,
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
                dest_name=source.name,
                dest_project_name=to_project,
            )
        )

    return plan


def _build_resume_plan(
    client: opik.Opik,
    to_project: str,
    checkpoint: "MigrationCheckpoint",
) -> MigrationPlan:
    """Build a cascade-only plan for a resumed migration.

    The dataset phase already completed on a prior run, so:

    * The source is the renamed ``<name>_v1`` dataset, resolved by the
      checkpoint's stored ``source_dataset_id`` (the original name now points
      at the DESTINATION dataset the first run created, so re-resolving by the
      user-supplied name would pick the wrong dataset).
    * The destination is the dataset carrying the ORIGINAL name under
      ``to_project`` -- what the first run's ``CreateDestination`` produced.
    * ``version_remap`` / ``item_id_remap`` / ``optimization_id_remap`` are
      rebuilt read-only from the destination (``reconstruct_remaps``); nothing
      is re-written, so no versions are duplicated.

    The returned plan contains only a ``CascadeExperiments`` action; the
    checkpoint drives per-experiment skip/resume inside the cascade.
    """
    if not checkpoint.source_dataset_id or not checkpoint.source_name_after_rename:
        raise MigrationError(
            "Cannot resume: the checkpoint is missing the source dataset id or "
            "post-rename name. Delete the destination dataset and re-run the "
            "migration from scratch."
        )

    ensure_destination_project_exists(client, to_project)

    # Destination dataset = the original name under --to-project (created by
    # the first run). ``checkpoint.dataset`` is that original name.
    dest = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
        lambda: client.get_dataset(name=checkpoint.dataset, project_name=to_project)
    )

    remaps = reconstruct_remaps(
        client.rest_client,
        source_dataset_id=checkpoint.source_dataset_id,
        source_name_after_rename=checkpoint.source_name_after_rename,
        source_project_name=None,
        dest_dataset_id=dest.id,
        dest_name=checkpoint.dataset,
        dest_project_name=to_project,
    )

    plan = MigrationPlan(
        source=ResolvedDataset(
            id=checkpoint.source_dataset_id,
            name=checkpoint.source_name_after_rename,
            project_name=None,
            description=None,
            type=None,
            visibility=None,
            tags=None,
        ),
        target_name=checkpoint.dataset,
        to_project=to_project,
        source_name_after_rename=checkpoint.source_name_after_rename,
        is_resume=True,
    )
    plan.version_remap.update(remaps.version_remap)
    plan.item_id_remap.update(remaps.item_id_remap)
    plan.optimization_id_remap.update(remaps.optimization_id_remap)

    plan.actions.append(
        CascadeExperiments(
            source_dataset_id=checkpoint.source_dataset_id,
            dest_name=checkpoint.dataset,
            dest_project_name=to_project,
        )
    )
    return plan
