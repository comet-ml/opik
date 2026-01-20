# mypy: disable-error-code=no-untyped-def

from typing import Any

import pytest

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.algorithms.evolutionary_optimizer.ops import population_ops
from tests.unit.test_helpers import (
    STANDARD_DATASET_ITEMS,
    make_mock_dataset,
    make_simple_metric,
)


def test_uses_validation_dataset_when_provided(monkeypatch: pytest.MonkeyPatch) -> None:
    """EvolutionaryOptimizer should evaluate against the validation dataset when supplied."""
    dataset_train = make_mock_dataset(
        STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
    )
    dataset_val = make_mock_dataset(
        STANDARD_DATASET_ITEMS, name="validation-dataset", dataset_id="dataset-456"
    )
    dataset_val.name = "validation-ds"

    optimizer = EvolutionaryOptimizer(
        model="gpt-4o",
        skip_perfect_score=False,
        population_size=2,
        num_generations=1,
    )

    calls: list[Any] = []

    def mock_evaluate_prompt(**kwargs: Any) -> float:
        calls.append(kwargs.get("dataset"))
        return 0.5

    monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate_prompt)
    monkeypatch.setattr(
        population_ops,
        "initialize_population",
        lambda **_kwargs: [ChatPrompt(system="s", user="u")] * optimizer.population_size,
    )

    prompt = ChatPrompt(system="test", user="{question}")
    optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset_train,
        validation_dataset=dataset_val,
        metric=make_simple_metric(),
        max_trials=3,
    )

    assert calls, "evaluate_prompt should be invoked"
    assert all(call is dataset_val for call in calls)


def test_validation_dataset_sets_history_split(monkeypatch: pytest.MonkeyPatch) -> None:
    """History entries should be tagged with validation split when using validation dataset."""
    dataset_train = make_mock_dataset(
        STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
    )
    dataset_val = make_mock_dataset(
        STANDARD_DATASET_ITEMS, name="validation-dataset", dataset_id="dataset-456"
    )

    optimizer = EvolutionaryOptimizer(
        model="gpt-4o",
        skip_perfect_score=False,
        population_size=2,
        num_generations=1,
    )

    def fake_evaluate(**_kwargs: Any) -> float:
        return 0.6

    monkeypatch.setattr("opik_optimizer.core.evaluation.evaluate", fake_evaluate)
    monkeypatch.setattr(
        population_ops,
        "initialize_population",
        lambda **_kwargs: [ChatPrompt(system="s", user="u")] * optimizer.population_size,
    )

    prompt = ChatPrompt(system="test", user="{question}")
    result = optimizer.optimize_prompt(
        prompt=prompt,
        dataset=dataset_train,
        validation_dataset=dataset_val,
        metric=make_simple_metric(),
        max_trials=3,
    )

    assert result.history
    first_round = result.history[0]
    assert first_round.get("dataset_split") == "validation"

