"""Smoke tests for unified history schema across optimizers."""

from __future__ import annotations

from typing import Any

from unittest.mock import MagicMock

from opik_optimizer.core.results import (
    OptimizationRound,
    OptimizationTrial,
)
from tests.unit.test_helpers import make_optimization_context
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import AlgorithmResult


def _assert_candidate_schema(entry: dict[str, Any]) -> None:
    candidates = entry.get("candidates") or []
    trials = entry.get("trials") or []
    if trials:
        assert candidates, "candidates should be recorded for trialed rounds"
    for cand in candidates:
        assert "candidate" in cand
        assert "score" in cand or "metrics" in cand


def _assert_trial_indices(history: list[dict[str, Any]]) -> None:
    seen = []
    for entry in history:
        for trial in entry.get("trials") or []:
            idx = trial.get("trial_index")
            if idx is not None:
                seen.append(idx)
    assert seen == sorted(seen)


def _assert_unique_candidate_ids(history: list[dict[str, Any]]) -> None:
    for entry in history:
        candidates = entry.get("candidates") or []
        ids = [
            cand.get("id")
            for cand in candidates
            if isinstance(cand, dict) and cand.get("id") is not None
        ]
        assert len(ids) == len(set(ids)), "candidate ids must be unique within a round"


def test_algorithm_result_accepts_typed_rounds(
    simple_chat_prompt: Any,
    mock_dataset: Any,
) -> None:
    dataset = mock_dataset(
        [{"id": "1", "question": "Q1", "answer": "A1"}],
        name="test-dataset",
        dataset_id="ds-1",
    )

    def metric_fn(*_args: Any) -> float:
        return 0.1

    metric_fn.__name__ = "metric_fn"
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric_fn,
        agent=MagicMock(),
    )
    trial = OptimizationTrial(trial_index=None, score=0.1, candidate=simple_chat_prompt)
    round_entry = OptimizationRound(round_index=0, trials=[trial], best_score=0.1)
    algo_result = AlgorithmResult(
        best_prompts=context.prompts,
        best_score=0.1,
        history=[round_entry],
    )

    class DummyOptimizer(BaseOptimizer):
        def get_config(self, context: Any) -> dict[str, Any]:
            return {}

        def get_optimizer_metadata(self) -> dict[str, Any]:
            return {}

    optimizer = DummyOptimizer(model="gpt-4")
    result = optimizer._build_final_result(algo_result, context)
    assert result.history
    assert result.history[0]["round_index"] == 0
