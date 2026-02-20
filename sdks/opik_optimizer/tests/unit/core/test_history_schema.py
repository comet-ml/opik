"""Smoke tests for unified history schema across optimizers."""

from __future__ import annotations

from typing import Any, cast

import pytest
from unittest.mock import MagicMock

from opik_optimizer.algorithms.evolutionary_optimizer.evolutionary_optimizer import (
    EvolutionaryOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer import (
    ParameterOptimizer,
)
from opik_optimizer.algorithms.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer import (
    FewShotBayesianOptimizer,
)
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_reflective_optimizer import (
    HierarchicalReflectiveOptimizer,
)
from opik_optimizer.algorithms.parameter_optimizer.ops.search_ops import (
    ParameterSearchSpace,
    ParameterSpec,
)
from opik_optimizer.algorithms.parameter_optimizer.types import ParameterType
from opik_optimizer.core import results as optimization_result
from opik_optimizer.core.results import (
    OptimizerCandidate,
    OptimizationResult,
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


def test_history_state_normalizes_scalar_candidate() -> None:
    state = optimization_result.OptimizationHistoryState()
    handle = state.start_round(round_index=0)
    state.record_trial(round_handle=handle, score=0.1, candidate="example")
    entries = state.get_entries()
    assert entries
    candidates = entries[0].get("candidates") or []
    assert candidates
    candidate_payload = candidates[0].get("candidate")
    assert isinstance(candidate_payload, dict)
    assert candidate_payload.get("value") == "example"


@pytest.mark.parametrize(
    "optimizer_cls",
    [
        EvolutionaryOptimizer,
        MetaPromptOptimizer,
        FewShotBayesianOptimizer,
        ParameterOptimizer,
        GepaOptimizer,
        HierarchicalReflectiveOptimizer,
    ],
)
def test_history_schema_smoke(
    optimizer_cls: type[BaseOptimizer],
    simple_chat_prompt: Any,
    mock_dataset: Any,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    optimizer = optimizer_cls(model="gpt-4o")
    dataset = mock_dataset(
        [{"id": "1", "question": "Q1", "answer": "A1"}],
        name="test-dataset",
        dataset_id="ds-1",
    )

    # Avoid real network/LLM calls in this smoke test.
    def fake_evaluate_with_result(**kwargs: Any) -> tuple[float, Any]:
        metric = kwargs.get("metric")
        metric_name = getattr(metric, "__name__", "metric")
        score_result = MagicMock()
        score_result.name = metric_name
        score_result.value = 0.1
        score_result.reason = None
        score_result.scoring_failed = False
        test_result = MagicMock()
        test_result.score_results = [score_result]
        mock_result = MagicMock()
        mock_result.test_results = [test_result]
        return 0.1, mock_result

    monkeypatch.setattr(
        "opik_optimizer.core.evaluation.evaluate_with_result",
        fake_evaluate_with_result,
    )
    # Stub Opik client to avoid network
    fake_client = MagicMock()
    fake_optimization = MagicMock()
    fake_optimization.id = "opt-1"
    fake_client.create_optimization.return_value = fake_optimization
    fake_client.get_optimization_by_id.return_value = fake_optimization
    optimizer._opik_client = cast(Any, fake_client)  # type: ignore[attr-defined]
    monkeypatch.setattr(
        "opik_optimizer.base_optimizer.opik_context.update_current_trace",
        lambda *a, **k: None,
    )
    # Short-circuit baseline/evaluate_prompt and run_optimization to deterministic outputs
    monkeypatch.setattr(
        BaseOptimizer, "_calculate_baseline", lambda self, ctx: 0.1, raising=False
    )
    dummy_history = [
        {
            "round_index": 0,
            "trials": [{"trial_index": 0, "score": 0.1}],
            "candidates": [{"candidate": simple_chat_prompt, "score": 0.1}],
        }
    ]
    monkeypatch.setattr(
        optimizer,
        "run_optimization",
        lambda ctx: AlgorithmResult(
            best_prompts=ctx.prompts,
            best_score=0.1,
            history=dummy_history,
            metadata={"trials_completed": 1},
        ),
        raising=False,
    )
    # Minimal config per optimizer
    if isinstance(optimizer, EvolutionaryOptimizer):
        optimizer.num_generations = 1
        optimizer.population_size = 2
    if isinstance(optimizer, MetaPromptOptimizer):
        optimizer.prompts_per_round = 1
    if isinstance(optimizer, ParameterOptimizer):
        optimizer.default_n_trials = 1

    if isinstance(optimizer, ParameterOptimizer):
        search_space = ParameterSearchSpace(
            parameters=[
                ParameterSpec(
                    name="temperature",
                    distribution=ParameterType.FLOAT,
                    low=0.0,
                    high=1.0,
                )
            ]
        )

        # Patch optimize_parameter to short-circuit and emit dummy history
        def fake_optimize_parameter(**kwargs: Any) -> OptimizationResult:
            return OptimizationResult(
                optimizer="ParameterOptimizer",
                prompt=simple_chat_prompt,
                score=0.1,
                metric_name="accuracy",
                history=dummy_history,
                details={"trials_completed": 1},
            )

        monkeypatch.setattr(
            optimizer, "optimize_parameter", fake_optimize_parameter, raising=False
        )
        result = optimizer.optimize_parameter(
            prompt=simple_chat_prompt,
            dataset=dataset,
            metric=lambda outputs, _: 0.1,
            parameter_space=search_space,
            max_trials=1,
        )
    else:
        result = optimizer.optimize_prompt(
            prompt=simple_chat_prompt,
            dataset=dataset,
            metric=lambda outputs, _: 0.1,
            max_trials=1,
        )

    history = result.history
    assert isinstance(history, list) and history
    _assert_candidate_schema(history[0])
    _assert_trial_indices(history)
    _assert_unique_candidate_ids(history)
    trials_completed = result.details.get("trials_completed")
    assert isinstance(trials_completed, int) and trials_completed >= 1
    # Ensure candidates follow the spec
    candidates = history[0].get("candidates") or []
    for cand in candidates:
        assert isinstance(
            cand.get("candidate"), (dict, OptimizerCandidate, type(simple_chat_prompt))
        )


def test_history_state_auto_assigns_trial_indices() -> None:
    state = optimization_result.OptimizationHistoryState()
    round_handle = state.start_round(round_index=0)
    state.record_trial(round_handle=round_handle, score=0.1)
    state.record_trial(round_handle=round_handle, score=0.2)
    state.end_round(round_handle=round_handle, best_score=0.2)
    round_handle_two = state.start_round(round_index=1)
    state.record_trial(round_handle=round_handle_two, score=0.3)
    state.record_trial(round_handle=round_handle_two, score=0.4)
    state.end_round(round_handle=round_handle_two, best_score=0.4)
    history = state.get_entries()
    assert history[0]["trials"][0]["trial_index"] == 0
    assert history[0]["trials"][1]["trial_index"] == 1
    assert history[1]["trials"][0]["trial_index"] == 2
    assert history[1]["trials"][1]["trial_index"] == 3


def test_history_state_defaults_stop_reason_completed() -> None:
    state = optimization_result.OptimizationHistoryState()
    round_handle = state.start_round(round_index=0)
    state.record_trial(round_handle=round_handle, score=0.1)
    state.end_round(round_handle=round_handle, best_score=0.1)
    history = state.get_entries()
    assert history[0]["stop_reason"] == "completed"
    assert history[0]["stopped"] is False
