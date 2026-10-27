"""HRPO ranks on one fixed sample but analyses a fresh training sample per iteration."""

from __future__ import annotations

from typing import Any

import pytest
from opik.evaluation.metrics import score_result

from tests.unit.characterization import fake_opik, harness


def _flat(dataset_item: dict[str, Any], llm_output: str) -> score_result.ScoreResult:
    return score_result.ScoreResult(name="flat", value=0.5, reason="no signal")


def test_each_iteration_analyses_new_items_while_ranking_stays_fixed(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    scenario = harness.build_scenario("S1_single")
    scenario.items = harness.dataset_items() + [
        dict(item, id=f"{item['id']}b") for item in harness.dataset_items()
    ]
    install_fakes(scenario)
    evaluated: list[frozenset[str]] = []

    def recording_evaluate(**kwargs: Any) -> Any:
        result = fake_opik.fake_evaluate(**kwargs)
        evaluated.append(
            frozenset(r.test_case.dataset_item_id for r in result.test_results)
        )
        return result

    for target in ("evaluate", "evaluate_optimization_trial"):
        monkeypatch.setattr(
            f"opik_optimizer.core.evaluation.opik_evaluator.{target}",
            recording_evaluate,
        )
    optimizer = harness.make_optimizer("HierarchicalReflectiveOptimizer")
    optimizer.convergence_threshold = -1.0  # keep iterating

    harness.run_optimizer(
        "HierarchicalReflectiveOptimizer",
        scenario,
        optimizer=optimizer,
        metric=_flat,
        n_samples=3,
        max_trials=12,
    )

    ranking_items = evaluated[0]
    analysis_samples = {items for items in evaluated if items != ranking_items}
    assert evaluated.count(ranking_items) > len(analysis_samples)
    assert len(analysis_samples) >= 2
