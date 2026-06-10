"""Optimization cascade for ``opik migrate dataset`` (Slice 5).

Recreates every optimization referencing the source dataset under the
destination project so the destination's Optimization Studio shows the
same rows + trial groupings as the source. Runs AFTER ``ReplayVersions``
and BEFORE ``CascadeExperiments``: the experiment cascade reads the
populated ``plan.optimization_id_remap`` to re-point each experiment's
``optimization_id`` FK at the destination optimization id.

Optimization is a real BE entity (its own UUID, own row in
``OptimizationDAO``, own ``dataset_id`` / ``project_id`` FKs). The
"group" view in the UI is computed by the BE at read time from
``numTrials`` + per-experiment aggregates; the persisted shape is one
Optimization row + N Experiment rows where
``Experiment.optimization_id == Optimization.id``.

Source-side enumeration uses ``find_optimizations(dataset_id=...)``
which is project-agnostic, mirroring how ``find_experiments`` works in
Slice 3 — cross-project optimizations are migrated transparently with
no extra plumbing.

Aggregated stats (``numTrials``, ``feedbackScores``, ``experimentScores``,
``baseline*`` / ``best*``, ``totalOptimizationCost``) are READ-ONLY on
the BE and recomputed from constituent experiments at read time. They're
NOT forwarded to ``create_optimization`` — the BE silently drops them
and the destination row would carry stale numbers if it didn't.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field
from typing import Dict, List, Optional

import opik.id_helpers as id_helpers_module
from opik.api_objects import rest_helpers
from opik.rest_api import OpikApi
from opik.rest_api.types.optimization_public import OptimizationPublic
from opik.rest_api.types.optimization_studio_config_public import (
    OptimizationStudioConfigPublic,
)
from opik.rest_api.types.optimization_studio_config_write import (
    OptimizationStudioConfigWrite,
)

from ..audit import AuditLog
from ._progress import ProgressCallback

LOGGER = logging.getLogger(__name__)

# ``find_optimizations`` is paginated; the cascade walks it to
# exhaustion. 100 is the same page size used by the Slice 3 experiment
# cascade, which keeps the per-call payload small without producing a
# request flood (production has tens to low-hundreds of optimizations
# per dataset at most).
_OPTIMIZATION_PAGE_SIZE = 100


@dataclass
class OptimizationCascadeResult:
    """Aggregated outcome of one ``CascadeOptimizations`` action.

    Populated as the cascade walks source optimizations. ``id_remap`` is
    copied onto ``plan.optimization_id_remap`` by the executor so the
    subsequent ``CascadeExperiments`` action can read it when re-pointing
    each experiment's ``optimization_id`` FK.
    """

    id_remap: Dict[str, str] = field(default_factory=dict)
    optimizations_total: int = 0
    optimizations_migrated: int = 0
    optimizations_skipped: int = 0
    skipped_optimizations: List[Dict[str, str]] = field(default_factory=list)


def cascade_optimizations(
    rest_client: OpikApi,
    *,
    source_dataset_id: str,
    target_dataset_name: str,
    target_project_name: str,
    audit: AuditLog,
    progress_callback: Optional[ProgressCallback] = None,
) -> OptimizationCascadeResult:
    """Enumerate source optimizations referencing ``source_dataset_id``
    and recreate each one at the destination with a fresh id.

    ``progress_callback(completed, total, label)`` fires once before each
    optimization so callers can drive a progress bar; matches the shape
    used by ``replay_all_versions`` and the experiment cascade.

    Returns
    -------
    OptimizationCascadeResult
        Carries ``id_remap`` (source optimization id -> newly-minted
        destination optimization id) and per-action counters. The
        executor copies ``id_remap`` onto ``plan.optimization_id_remap``
        so ``CascadeExperiments`` can read it when re-pointing
        experiment FK references.
    """
    result = OptimizationCascadeResult()

    source_optimizations = list(
        _list_source_optimizations(rest_client, source_dataset_id)
    )
    result.optimizations_total = len(source_optimizations)

    if not source_optimizations:
        LOGGER.info(
            "No optimizations reference dataset %s; cascade is a no-op.",
            source_dataset_id,
        )
        # Fire one terminal callback so callers can finalize a progress
        # display (e.g. close a Rich Progress block) even when there's
        # nothing to migrate.
        if progress_callback is not None:
            progress_callback(0, 0, "done")
        return result

    total = len(source_optimizations)
    for index, optimization in enumerate(source_optimizations):
        label = optimization.name or optimization.id or f"<optimization[{index}]>"
        if progress_callback is not None:
            progress_callback(index, total, label)

        source_id = optimization.id
        if not source_id:
            # Defensive: the BE should always return an id. Skip rather
            # than fail — we can't build a remap entry without a source
            # id, and other optimizations are still recoverable.
            LOGGER.warning(
                "Source optimization missing id; skipping. payload=%r",
                optimization,
            )
            result.optimizations_skipped += 1
            result.skipped_optimizations.append(
                {
                    "id": "",
                    "name": optimization.name or "",
                    "reason": "source optimization missing id",
                }
            )
            continue

        destination_id = id_helpers_module.generate_id()
        studio_config_write = _studio_config_public_to_write(optimization.studio_config)

        # Read-only aggregates (num_trials, feedback_scores, baseline_*,
        # best_*, total_optimization_cost) are intentionally omitted —
        # the BE recomputes them from constituent experiments at read
        # time and silently drops them on write.
        rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda src=optimization, new_id=destination_id, cfg=studio_config_write: (
                rest_client.optimizations.create_optimization(
                    id=new_id,
                    name=src.name,
                    dataset_name=target_dataset_name,
                    project_name=target_project_name,
                    objective_name=src.objective_name,
                    status=src.status,
                    metadata=src.metadata,
                    studio_config=cfg,
                    last_updated_at=src.last_updated_at,
                )
            )
        )

        result.id_remap[source_id] = destination_id
        result.optimizations_migrated += 1

        audit.record(
            type="migrate_optimization",
            status="ok",
            details={
                "type": "migrate_optimization",
                "source_id": source_id,
                "destination_id": destination_id,
                "name": optimization.name,
                "objective_name": optimization.objective_name,
                # Use ``optimization_status`` rather than ``status`` so the
                # entry.update(details) merge in AuditLog.record doesn't
                # shadow the audit-level success/failure status.
                "optimization_status": optimization.status,
            },
        )

    if progress_callback is not None:
        progress_callback(total, total, "done")

    return result


def _studio_config_public_to_write(
    public: Optional[OptimizationStudioConfigPublic],
) -> Optional[OptimizationStudioConfigWrite]:
    """Reconstruct the Read variant as the Write variant.

    The two Fern-generated types are structurally identical (same
    fields, same nesting); only the class identity differs. Pydantic's
    ``model_validate`` over ``model_dump`` round-trips the fidelity
    without us having to hand-map each field. ``extra="allow"`` on both
    sides preserves any forward-compatible BE additions.
    """
    if public is None:
        return None
    return OptimizationStudioConfigWrite.model_validate(public.model_dump())


def _list_source_optimizations(
    rest_client: OpikApi, source_dataset_id: str
) -> List[OptimizationPublic]:
    """Page through ``find_optimizations(dataset_id=...)`` to exhaustion.

    No filtering by project: optimizations are project-scoped at write
    time but tied to the dataset, so a single ``find_optimizations`` call
    returns every row regardless of which project it lives in (mirrors
    how ``find_experiments`` works in Slice 3).
    """
    collected: List[OptimizationPublic] = []
    page = 1
    while True:
        response = rest_helpers.ensure_rest_api_call_respecting_rate_limit(
            lambda: rest_client.optimizations.find_optimizations(
                dataset_id=source_dataset_id,
                page=page,
                size=_OPTIMIZATION_PAGE_SIZE,
            )
        )
        page_content = response.content or []
        collected.extend(page_content)
        if len(page_content) < _OPTIMIZATION_PAGE_SIZE:
            break
        page += 1
    return collected
