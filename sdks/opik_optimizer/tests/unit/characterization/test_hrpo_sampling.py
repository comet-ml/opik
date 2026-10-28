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

    ranking_items = evaluated[0]  # the baseline's
    analysis_samples = [items for items in evaluated if items != ranking_items]
    assert evaluated.count(ranking_items) >= 2  # candidates ranked on the same items
    assert len(set(analysis_samples)) == len(analysis_samples) >= 2


def test_analysis_on_training_items_stays_out_of_validation_trials(
    install_fakes: Any, monkeypatch: pytest.MonkeyPatch
) -> None:
    from tests.unit.test_helpers import make_mock_dataset

    scenario = harness.build_scenario("S1_single")
    install_fakes(scenario)
    validation = make_mock_dataset(
        scenario.items, name="characterization-validation", dataset_id="ds-validation"
    )
    logged: list[tuple[str, str]] = []

    def recording_trial(**kwargs: Any) -> Any:
        logged.append((kwargs.get("experiment_type", "trial"), kwargs["dataset"].id))
        return fake_opik.fake_evaluate(**kwargs)

    monkeypatch.setattr(
        "opik_optimizer.core.evaluation.opik_evaluator.evaluate_optimization_trial",
        recording_trial,
    )

    harness.run_optimizer(
        "HierarchicalReflectiveOptimizer", scenario, validation_dataset=validation
    )

    # Trials are ranked on validation items; the failure analysis reads training
    # items and is logged apart, so the UI never averages the two.
    assert {ds for kind, ds in logged if kind == "trial"} == {"ds-validation"}
    assert ("mini-batch", "ds-characterization") in logged
