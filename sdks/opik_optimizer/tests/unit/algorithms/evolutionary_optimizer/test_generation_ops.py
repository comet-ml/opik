# mypy: disable-error-code=no-untyped-def
# Not named test_evolutionary_optimizer_*: those get run_optimization stubbed out
# by the autouse fixtures in tests/unit/fixtures/evolutionary_optimizer_fixtures.py.

import pytest

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.algorithms.evolutionary_optimizer.ops import population_ops
from tests.unit.test_helpers import (
    STANDARD_DATASET_ITEMS,
    make_mock_dataset,
    make_simple_metric,
)


def test_stagnation_early_stop_records_final_generation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    optimizer = EvolutionaryOptimizer(
        model="gpt-4o",
        skip_perfect_score=False,
        population_size=2,
        num_generations=3,
        enable_moo=False,
        verbose=0,
    )
    optimizer.DEFAULT_EARLY_STOPPING_GENERATIONS = 1
    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: 0.6)
    monkeypatch.setattr(
        population_ops,
        "initialize_population",
        lambda **_kwargs: [ChatPrompt(system="s", user="u")]
        * optimizer.population_size,
    )
    # A generation that changes nothing: no improvement, so it stagnates at gen 1.
    monkeypatch.setattr(
        optimizer, "_run_generation", lambda _idx, population, *_a: (population, 0)
    )

    result = optimizer.optimize_prompt(
        prompt=ChatPrompt(system="test", user="{question}"),
        dataset=make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        ),
        metric=make_simple_metric(),
        max_trials=20,
    )

    assert result.details["finish_reason"] == "no_improvement"
    # Initial population round + the stagnant generation-1 round.
    assert [round_["round_index"] for round_ in result.history] == [0, 1]
    assert result.history[-1].get("candidates")
