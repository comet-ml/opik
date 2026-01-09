"""
Unit tests for early-return behavior when baseline score meets the threshold.
"""

from __future__ import annotations

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik import Dataset
from opik_optimizer import ChatPrompt
from opik_optimizer.algorithms.evolutionary_optimizer.ops import (
    evaluation_ops,
    population_ops,
)
from opik_optimizer.algorithms.evolutionary_optimizer.evolutionary_optimizer import (
    EvolutionaryOptimizer,
)
from opik_optimizer.algorithms.few_shot_bayesian_optimizer.few_shot_bayesian_optimizer import (
    FewShotBayesianOptimizer,
)
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import GepaOptimizer
from opik_optimizer.algorithms.hierarchical_reflective_optimizer.hierarchical_reflective_optimizer import (
    HierarchicalReflectiveOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.meta_prompt_optimizer import (
    MetaPromptOptimizer,
)
from opik_optimizer.algorithms.meta_prompt_optimizer.ops import candidate_ops
from opik_optimizer.algorithms.parameter_optimizer.parameter_optimizer import (
    ParameterOptimizer,
)
from opik_optimizer.algorithms.parameter_optimizer.parameter_search_space import (
    ParameterSearchSpace,
)
from opik_optimizer.algorithms.parameter_optimizer.parameter_spec import ParameterSpec
from opik_optimizer.algorithms.parameter_optimizer.search_space_types import (
    ParameterType,
)


def _metric(dataset_item: dict[str, Any], llm_output: str) -> float:
    return 1.0


def _make_dataset() -> MagicMock:
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test-dataset"
    dataset.id = "dataset-123"
    dataset.get_items.return_value = [{"id": "1", "question": "Q1", "answer": "A1"}]
    return dataset


def _assert_early_stop(result: Any) -> None:
    assert result.details["stopped_early"] is True
    assert result.details["stopped_early_reason"] == "baseline_score_met_threshold"
    assert result.details["perfect_score"] == 0.95
    assert result.details["skip_perfect_score"] is True
    assert result.initial_score == result.score
    assert result.score >= result.details["perfect_score"]


pytestmark = pytest.mark.usefixtures("disable_rate_limiting")


def test_meta_prompt_skips_on_perfect_score(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()
    optimizer = MetaPromptOptimizer(model="gpt-4o", perfect_score=0.95)

    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
    monkeypatch.setattr(
        candidate_ops,
        "generate_agent_bundle_candidates",
        lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
    )

    prompt = ChatPrompt(system="baseline", user="{question}")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=_metric,
        max_trials=1,
    )

    _assert_early_stop(result)


def test_evolutionary_skips_on_perfect_score(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()
    optimizer = EvolutionaryOptimizer(
        model="gpt-4o", perfect_score=0.95, enable_moo=False
    )

    monkeypatch.setattr(evaluation_ops, "evaluate_bundle", lambda *args, **kwargs: 0.96)
    monkeypatch.setattr(
        population_ops,
        "initialize_population",
        lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
    )

    prompt = ChatPrompt(system="baseline", user="{question}")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=_metric,
        max_trials=1,
    )

    _assert_early_stop(result)
    assert result.details["trials_used"] == 0


def test_few_shot_skips_on_perfect_score(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()
    optimizer = FewShotBayesianOptimizer(model="gpt-4o", perfect_score=0.95)

    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
    monkeypatch.setattr(
        optimizer,
        "_run_optimization",
        lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
    )

    prompt = ChatPrompt(system="baseline", user="{question}")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=_metric,
        max_trials=1,
    )

    _assert_early_stop(result)


def test_parameter_optimizer_skips_on_perfect_score(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()
    optimizer = ParameterOptimizer(model="gpt-4o", perfect_score=0.95)

    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)
    monkeypatch.setattr(
        "optuna.create_study",
        lambda **kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
    )

    prompt = ChatPrompt(system="baseline", user="{question}")
    parameter_space = ParameterSearchSpace(
        parameters=[
            ParameterSpec(
                name="temperature",
                distribution=ParameterType.FLOAT,
                low=0.0,
                high=1.0,
            )
        ]
    )
    result = optimizer.optimize_parameter(
        prompt=prompt,
        dataset=dataset,
        metric=_metric,
        parameter_space=parameter_space,
        max_trials=1,
    )

    _assert_early_stop(result)
    assert result.details["n_trials"] == 0


def test_gepa_skips_on_perfect_score(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()
    optimizer = GepaOptimizer(model="gpt-4o", perfect_score=0.95)

    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.96)

    prompt = ChatPrompt(system="baseline", user="{question}")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=_metric,
        max_trials=1,
    )

    _assert_early_stop(result)
    assert result.details["trials_used"] == 0


def test_hierarchical_reflective_skips_on_perfect_score(
    mock_opik_client: Callable[..., MagicMock],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    mock_opik_client()
    dataset = _make_dataset()
    optimizer = HierarchicalReflectiveOptimizer(model="gpt-4o", perfect_score=0.95)

    score_result = MagicMock(value=0.96)
    test_result = MagicMock(score_results=[score_result])
    experiment_result = MagicMock(test_results=[test_result])
    monkeypatch.setattr(
        optimizer, "evaluate_prompt", lambda **kwargs: experiment_result
    )

    prompt = ChatPrompt(system="baseline", user="{question}")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset,
        metric=_metric,
        max_trials=1,
    )

    _assert_early_stop(result)
    assert result.details["trials_used"] == 0
