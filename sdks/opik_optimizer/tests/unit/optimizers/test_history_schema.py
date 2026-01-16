"""Smoke tests for unified history schema across optimizers."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.algorithms.evolutionary_optimizer.evolutionary_optimizer import EvolutionaryOptimizer
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import MetaPromptOptimizer
from opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer import ParameterOptimizer
from opik_optimizer.algorithms.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer import FewShotBayesianOptimizer
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_reflective_optimizer import (
    HierarchicalReflectiveOptimizer,
)
from opik_optimizer.optimization_result import OptimizerCandidate


def _assert_candidate_schema(entry: dict[str, Any]) -> None:
    candidates = entry.get("candidates") or []
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
def test_history_schema_smoke(optimizer_cls: Any, simple_chat_prompt, mock_dataset):
    optimizer = optimizer_cls(model="gpt-4o")
    # Minimal config per optimizer
    if isinstance(optimizer, EvolutionaryOptimizer):
        optimizer.num_generations = 1
        optimizer.population_size = 2
    if isinstance(optimizer, MetaPromptOptimizer):
        optimizer.total_rounds = 1
        optimizer.prompts_per_round = 1
    if isinstance(optimizer, FewShotBayesianOptimizer):
        optimizer.num_task_examples = 1
        optimizer.max_trials = 1
    if isinstance(optimizer, ParameterOptimizer):
        optimizer.default_n_trials = 1
    if isinstance(optimizer, GepaOptimizer):
        optimizer.max_trials = 1
    if isinstance(optimizer, HierarchicalReflectiveOptimizer):
        optimizer.max_trials = 1

    result = optimizer.optimize_prompt(
        prompt=simple_chat_prompt,
        dataset=mock_dataset,
        metric=lambda outputs, _: 0.1,
        max_trials=1,
    )

    history = result.history
    assert isinstance(history, list) and history
    _assert_candidate_schema(history[0])
    _assert_trial_indices(history)
    assert result.details.get("trials_completed") >= 1
    # Ensure candidates follow the spec
    candidates = history[0].get("candidates") or []
    for cand in candidates:
        assert isinstance(cand.get("candidate"), (dict, OptimizerCandidate, type(simple_chat_prompt)))
