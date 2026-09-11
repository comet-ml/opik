"""Tests for ``opik migrate dataset`` Slice 5 — optimization cascade.

Slice 5 sits between Slice 2 (``ReplayVersions``) and Slice 3
(``CascadeExperiments``). It recreates every optimization referencing
the source dataset under the destination project and populates
``plan.optimization_id_remap`` so the experiment cascade can re-point
each experiment's ``optimization_id`` FK.

Scope this module covers (ticket AC for OPIK-6532):

* Optimization fidelity round-trip (name / objective_name / status /
  metadata / studio_config carried verbatim)
* READ-ONLY aggregates (num_trials / feedback_scores / baseline_* /
  best_* / total_optimization_cost) not forwarded
* Fresh client-side UUID minted per source optimization; ``id_remap``
  populated with source -> destination
* Empty optimization (zero trials) round-trips and produces a remap entry
* Zero-optimization datasets are a no-op (returns empty result, no
  REST call)
* Per-optimization ``migrate_optimization`` audit record emitted
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional
from unittest.mock import MagicMock

import pytest

from opik.cli.migrate.audit import AuditLog
from opik.cli.migrate.datasets import optimizations as optimizations_module
from opik.cli.migrate.datasets.optimizations import cascade_optimizations


# ---------------------------------------------------------------------------
# Stand-in wire shapes for the cascade's reads.
#
# The cascade reads ``OptimizationPublic`` from ``find_optimizations`` and
# writes via ``create_optimization``. Constructing the real pydantic
# models would impose ~20 fields per row; the cascade only inspects a
# handful, so we use plain objects with the fields it actually reads.
# ---------------------------------------------------------------------------


class _Optimization:
    def __init__(
        self,
        *,
        id: str,
        name: Optional[str] = "opt",
        objective_name: str = "accuracy",
        status: str = "completed",
        dataset_name: str = "MyDataset",
        dataset_id: Optional[str] = "src-dataset-1",
        metadata: Optional[Any] = None,
        studio_config: Optional[Any] = None,
        last_updated_at: Optional[Any] = None,
        # READ-ONLY aggregates -- attached so we can assert the cascade
        # does NOT forward them on the create call.
        num_trials: Optional[int] = None,
        feedback_scores: Optional[List[Any]] = None,
        experiment_scores: Optional[List[Any]] = None,
        baseline_objective_score: Optional[float] = None,
        best_objective_score: Optional[float] = None,
        baseline_duration: Optional[float] = None,
        best_duration: Optional[float] = None,
        baseline_cost: Optional[float] = None,
        best_cost: Optional[float] = None,
        total_optimization_cost: Optional[float] = None,
    ) -> None:
        self.id = id
        self.name = name
        self.objective_name = objective_name
        self.status = status
        self.dataset_name = dataset_name
        self.dataset_id = dataset_id
        self.metadata = metadata
        self.studio_config = studio_config
        self.last_updated_at = last_updated_at
        self.num_trials = num_trials
        self.feedback_scores = feedback_scores
        self.experiment_scores = experiment_scores
        self.baseline_objective_score = baseline_objective_score
        self.best_objective_score = best_objective_score
        self.baseline_duration = baseline_duration
        self.best_duration = best_duration
        self.baseline_cost = baseline_cost
        self.best_cost = best_cost
        self.total_optimization_cost = total_optimization_cost


class _Page:
    def __init__(self, content: List[_Optimization]) -> None:
        self.content = content


def _audit() -> AuditLog:
    """Real AuditLog so the cascade's per-optimization ``record`` calls
    land in a list we can assert on. The audit log carries no side
    effects until ``write`` is called, so using the real type in tests
    is cheaper than building a coupled mock."""
    return AuditLog(command="migrate dataset", args={})


def _cascade_rest_client(
    pages: List[_Page],
    *,
    create_side_effect: Optional[Any] = None,
) -> MagicMock:
    """Build a rest_client mock for the optimization cascade.

    ``find_optimizations`` is called once per page until a short page
    breaks the loop. ``create_optimization`` is captured so individual
    tests can assert on its kwargs.
    """
    rest_client = MagicMock()
    rest_client.optimizations.find_optimizations.side_effect = pages
    if create_side_effect is not None:
        rest_client.optimizations.create_optimization.side_effect = create_side_effect
    else:
        rest_client.optimizations.create_optimization.return_value = None
    return rest_client


# ---------------------------------------------------------------------------
# Cascade tests
# ---------------------------------------------------------------------------


class TestCascadeOptimizations:
    def test_no_source_optimizations__noop_no_remap_no_create_calls(self) -> None:
        # Empty result page -> no create_optimization calls, empty
        # remap, counters at zero. Slice 5 always emits the action; the
        # cascade handles the zero case quietly.
        rest_client = _cascade_rest_client([_Page([])])

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        assert result.id_remap == {}
        assert result.optimizations_total == 0
        assert result.optimizations_migrated == 0
        assert result.optimizations_skipped == 0
        rest_client.optimizations.create_optimization.assert_not_called()

    def test_one_optimization__fidelity_fields_forwarded_and_remap_populated(
        self,
    ) -> None:
        # Full fidelity copy of the fields the BE round-trips:
        # name, objective_name, status, metadata, studio_config,
        # last_updated_at. Dest dataset_name + project_name are taken
        # from the action, NOT from the source row (the source name was
        # renamed to <name>_v1 by the time the cascade runs).
        opt = _Optimization(
            id="src-opt-1",
            name="overnight-tune",
            objective_name="f1",
            status="completed",
            metadata={"trial_count": 12, "user_note": "kept"},
            studio_config=None,
            num_trials=12,
            baseline_objective_score=0.5,
            best_objective_score=0.81,
            total_optimization_cost=4.20,
        )
        rest_client = _cascade_rest_client([_Page([opt])])

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        rest_client.optimizations.create_optimization.assert_called_once()
        kwargs = rest_client.optimizations.create_optimization.call_args.kwargs
        # Fresh destination id -- not the source id.
        assert kwargs["id"] != "src-opt-1"
        assert kwargs["id"] == result.id_remap["src-opt-1"]
        # Fidelity fields forwarded verbatim.
        assert kwargs["name"] == "overnight-tune"
        assert kwargs["objective_name"] == "f1"
        assert kwargs["status"] == "completed"
        assert kwargs["metadata"] == {"trial_count": 12, "user_note": "kept"}
        # Destination scoping comes from the action, not the source row.
        assert kwargs["dataset_name"] == "MyDataset"
        assert kwargs["project_name"] == "DestProject"

    def test_readonly_aggregates__not_forwarded_on_create(self) -> None:
        # ``num_trials`` / ``feedback_scores`` / ``baseline_*`` / ``best_*``
        # / ``total_optimization_cost`` are recomputed by the BE at read
        # time from constituent experiments. The cascade must NOT forward
        # them -- if it did, they'd be silently dropped on write today
        # but might persist (with stale numbers) under a future BE
        # schema change.
        opt = _Optimization(
            id="src-opt-1",
            num_trials=99,
            feedback_scores=[MagicMock()],
            experiment_scores=[MagicMock()],
            baseline_objective_score=0.1,
            best_objective_score=0.9,
            baseline_duration=10.0,
            best_duration=2.0,
            baseline_cost=1.0,
            best_cost=0.5,
            total_optimization_cost=42.0,
        )
        rest_client = _cascade_rest_client([_Page([opt])])

        cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        kwargs = rest_client.optimizations.create_optimization.call_args.kwargs
        for forbidden in (
            "num_trials",
            "feedback_scores",
            "experiment_scores",
            "baseline_objective_score",
            "best_objective_score",
            "baseline_duration",
            "best_duration",
            "baseline_cost",
            "best_cost",
            "total_optimization_cost",
        ):
            assert forbidden not in kwargs, (
                f"{forbidden} is BE-computed; cascade must not forward it"
            )

    def test_empty_optimization__zero_trials__still_migrated(self) -> None:
        # Optimization with zero constituent experiments is still a
        # user-visible row in the Optimization Studio UI; migrating it
        # preserves the user's setup even if no trials ran yet.
        opt = _Optimization(id="src-opt-1", num_trials=0, name="never-ran")
        rest_client = _cascade_rest_client([_Page([opt])])

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        assert result.optimizations_migrated == 1
        assert "src-opt-1" in result.id_remap
        rest_client.optimizations.create_optimization.assert_called_once()

    def test_multiple_optimizations__each_gets_distinct_destination_id(
        self,
    ) -> None:
        opt1 = _Optimization(id="src-opt-1", name="run-a")
        opt2 = _Optimization(id="src-opt-2", name="run-b")
        opt3 = _Optimization(id="src-opt-3", name="run-c")
        rest_client = _cascade_rest_client([_Page([opt1, opt2, opt3])])

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        assert result.optimizations_migrated == 3
        assert set(result.id_remap.keys()) == {
            "src-opt-1",
            "src-opt-2",
            "src-opt-3",
        }
        # Destination ids must be unique (fresh UUIDs).
        assert len(set(result.id_remap.values())) == 3

    def test_audit__per_optimization_record_emitted(self) -> None:
        # Each successful migration emits a ``migrate_optimization`` audit
        # record carrying source_id -> destination_id mapping plus
        # name / objective_name / status so users can trace what moved.
        opt = _Optimization(
            id="src-opt-1",
            name="overnight",
            objective_name="accuracy",
            status="completed",
        )
        rest_client = _cascade_rest_client([_Page([opt])])
        audit = _audit()

        cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=audit,
        )

        migrate_records = [
            r for r in audit.actions if r["type"] == "migrate_optimization"
        ]
        assert len(migrate_records) == 1
        record = migrate_records[0]
        # Audit-level status conveys action success/failure -- must not
        # be shadowed by the optimization's own status.
        assert record["status"] == "ok"
        assert record["source_id"] == "src-opt-1"
        assert record["destination_id"] != "src-opt-1"
        assert record["name"] == "overnight"
        assert record["objective_name"] == "accuracy"
        # Optimization's own status lives under a non-shadowing key.
        assert record["optimization_status"] == "completed"

    def test_pagination__exhausts_pages_until_short(self) -> None:
        # find_optimizations is paginated; the cascade walks until a
        # short page (< page_size). With page_size=100 in production,
        # two pages of 100 and a final short page of 50 should fire
        # three reads in order.
        page_size = optimizations_module._OPTIMIZATION_PAGE_SIZE
        page_a = _Page([_Optimization(id=f"src-{i}") for i in range(page_size)])
        page_b = _Page(
            [_Optimization(id=f"src-{page_size + i}") for i in range(page_size)]
        )
        page_c = _Page([_Optimization(id=f"src-{2 * page_size + i}") for i in range(3)])
        rest_client = _cascade_rest_client([page_a, page_b, page_c])

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        assert result.optimizations_total == 2 * page_size + 3
        # 3 reads + N writes.
        assert rest_client.optimizations.find_optimizations.call_count == 3

    def test_create_optimization_raises__exception_propagates(self) -> None:
        # The BE returns 409 on id conflict (we mint client-side UUIDs
        # so within-run collisions are infinitesimal but possible across
        # retries). Surface as a cascade error rather than silently
        # continuing; the audit log's surrounding ``failed`` entry will
        # capture partial progress via the umbrella action.
        opt = _Optimization(id="src-opt-1")
        rest_client = _cascade_rest_client(
            [_Page([opt])],
            create_side_effect=RuntimeError("409 conflict"),
        )

        with pytest.raises(RuntimeError, match="409 conflict"):
            cascade_optimizations(
                rest_client,
                source_dataset_id="src-dataset-1",
                target_dataset_name="MyDataset",
                target_project_name="DestProject",
                audit=_audit(),
            )

    def test_source_missing_id__skipped_not_remapped(self) -> None:
        # Defensive path: BE returns an optimization without an id.
        # Skip rather than fail -- other optimizations are still
        # recoverable, and a non-zero ``optimizations_skipped`` counter
        # in the audit surfaces the malformed row for investigation.
        ok = _Optimization(id="src-opt-1")
        bad = _Optimization(id="")
        rest_client = _cascade_rest_client([_Page([ok, bad])])

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-dataset-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )

        assert result.optimizations_migrated == 1
        assert result.optimizations_skipped == 1
        assert list(result.id_remap.keys()) == ["src-opt-1"]


class TestCascadeOptimizationsWithExperiments:
    """End-to-end planner-shape assertion: the optimization remap built
    by Slice 5 is observable to Slice 3's experiment cascade via the
    shared ``MigrationPlan``. The actual experiment-cascade FK forwarding
    is asserted in ``test_migrate_dataset_experiments_cascade``; this
    test only pins the wiring (action ordering + remap propagation).
    """

    def test_plan_remap__populated_by_cascade__readable_by_subsequent_actions(
        self,
    ) -> None:
        from opik.cli.migrate.datasets.planner import MigrationPlan
        from opik.cli.migrate.datasets.resolver import ResolvedDataset

        opt = _Optimization(id="src-opt-1", name="trained")
        rest_client = _cascade_rest_client([_Page([opt])])
        plan = MigrationPlan(
            source=ResolvedDataset(
                id="src-1",
                name="MyDataset",
                project_name=None,
                description=None,
                visibility=None,
                tags=None,
                type="dataset",
            ),
            target_name="MyDataset",
            to_project="DestProject",
        )

        result = cascade_optimizations(
            rest_client,
            source_dataset_id="src-1",
            target_dataset_name="MyDataset",
            target_project_name="DestProject",
            audit=_audit(),
        )
        plan.optimization_id_remap.update(result.id_remap)

        # Subsequent CascadeExperiments would read plan.optimization_id_remap;
        # the wiring works as long as result.id_remap propagates here.
        assert plan.optimization_id_remap == result.id_remap
        assert "src-opt-1" in plan.optimization_id_remap


# ---------------------------------------------------------------------------
# Studio config Read -> Write reconstruction
# ---------------------------------------------------------------------------


class TestStudioConfigReconstruction:
    """The Fern-generated Read and Write variants of
    ``OptimizationStudioConfig`` are structurally identical but
    nominally distinct; the cascade has to reconstruct the Write
    variant from the Read variant so the typed ``create_optimization``
    parameter is satisfied. Pin the round-trip so any future Fern
    regeneration that changes the shape on one side gets caught.
    """

    def test_none_in_none_out(self) -> None:
        from opik.cli.migrate.datasets.optimizations import (
            _studio_config_public_to_write,
        )

        assert _studio_config_public_to_write(None) is None

    def test_round_trip__fields_preserved(self) -> None:
        from opik.cli.migrate.datasets.optimizations import (
            _studio_config_public_to_write,
        )
        from opik.rest_api.types.optimization_studio_config_public import (
            OptimizationStudioConfigPublic,
        )
        from opik.rest_api.types.studio_evaluation_public import (
            StudioEvaluationPublic,
        )
        from opik.rest_api.types.studio_llm_model_public import (
            StudioLlmModelPublic,
        )
        from opik.rest_api.types.studio_message_public import StudioMessagePublic
        from opik.rest_api.types.studio_metric_public import StudioMetricPublic
        from opik.rest_api.types.studio_optimizer_public import (
            StudioOptimizerPublic,
        )
        from opik.rest_api.types.studio_prompt_public import StudioPromptPublic

        public = OptimizationStudioConfigPublic(
            dataset_name="MyDataset",
            prompt=StudioPromptPublic(
                messages=[StudioMessagePublic(role="system", content="you are helpful")]
            ),
            llm_model=StudioLlmModelPublic(
                model="gpt-4o", parameters={"temperature": 0.2}
            ),
            evaluation=StudioEvaluationPublic(
                metrics=[StudioMetricPublic(type="accuracy")]
            ),
            optimizer=StudioOptimizerPublic(type="grid", parameters={"steps": 5}),
        )
        write = _studio_config_public_to_write(public)
        assert write is not None
        # Round-trip via model_dump -- the typed Write variant accepts
        # extra fields so this also covers any forward-compatible BE
        # additions on the Public variant.
        write_dump: Dict[str, Any] = write.model_dump()
        public_dump: Dict[str, Any] = public.model_dump()
        assert write_dump == public_dump
