# mypy: disable-error-code=no-untyped-def

from collections.abc import Callable
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt, EvolutionaryOptimizer
from opik_optimizer.algorithms.evolutionary_optimizer.ops import population_ops
from tests.unit.fixtures import assert_baseline_early_stop, make_baseline_prompt
from tests.unit.test_helpers import (
    STANDARD_DATASET_ITEMS,
    make_mock_dataset,
    make_simple_metric,
)


class TestEvolutionaryOptimizerEarlyStop:
    def test_skips_on_perfect_score(
        self,
        mock_opik_client: Callable[..., MagicMock],
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        mock_opik_client()
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o", perfect_score=0.95, enable_moo=False
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: 0.96)
        monkeypatch.setattr(
            population_ops,
            "initialize_population",
            lambda **_kwargs: (_ for _ in ()).throw(AssertionError("should not run")),
        )

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )

        assert_baseline_early_stop(result, perfect_score=0.95)

    def test_early_stop_reports_at_least_one_trial(
        self,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify EvolutionaryOptimizer early stop reports at least 1 trial."""
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o",
            skip_perfect_score=True,
            perfect_score=0.95,
        )

        monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **_kwargs: 0.96)

        prompt = make_baseline_prompt()
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=1,
        )

        assert_baseline_early_stop(
            result, perfect_score=0.95, trials_completed=1, history_len=1
        )

    def test_optimization_tracks_trials_and_rounds(
        self,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify EvolutionaryOptimizer tracks trials/rounds during actual optimization."""
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o",
            skip_perfect_score=False,
            population_size=2,
            num_generations=2,
        )

        evaluation_count = [0]

        def mock_evaluate_prompt(**_kwargs: Any) -> float:
            evaluation_count[0] += 1
            return 0.6

        monkeypatch.setattr(optimizer, "evaluate_prompt", mock_evaluate_prompt)

        prompt = ChatPrompt(system="test", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=10,
        )

        assert result.details["trials_completed"] >= 1
        assert len(result.history) > 0
        assert evaluation_count[0] > 1

    def test_initial_population_records_round(
        self,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Initial population should record a round with candidate entries."""
        dataset = make_mock_dataset(
            STANDARD_DATASET_ITEMS, name="test-dataset", dataset_id="dataset-123"
        )
        optimizer = EvolutionaryOptimizer(
            model="gpt-4o",
            skip_perfect_score=False,
            population_size=2,
            num_generations=1,
            enable_moo=False,
        )

        def fake_evaluate_with_result(**kwargs: Any) -> tuple[float, Any]:
            metric = kwargs.get("metric")
            metric_name = getattr(metric, "__name__", "metric")
            score_result = MagicMock()
            score_result.name = metric_name
            score_result.value = 0.6
            score_result.reason = None
            score_result.scoring_failed = False
            test_result = MagicMock()
            test_result.score_results = [score_result]
            mock_result = MagicMock()
            mock_result.test_results = [test_result]
            return 0.6, mock_result

        monkeypatch.setattr(
            "opik_optimizer.core.evaluation.evaluate_with_result",
            fake_evaluate_with_result,
        )
        monkeypatch.setattr(
            population_ops,
            "initialize_population",
            lambda **_kwargs: [ChatPrompt(system="s", user="u")]
            * optimizer.population_size,
        )

        prompt = ChatPrompt(system="test", user="{question}")
        result = optimizer.optimize_prompt(
            prompt=prompt,
            dataset=dataset,
            metric=make_simple_metric(),
            max_trials=10,
        )

        assert result.history
        candidates = result.history[0].get("candidates") or []
        assert len(candidates) >= 1
        if len(candidates) == 1:
            assert candidates[0].get("id") == "fallback"
