# mypy: disable-error-code=no-untyped-def

from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

from gepa.utils.stop_condition import NoImprovementStopper, ScoreThresholdStopper

from opik_optimizer import GepaOptimizer


def _make_mock_gepa_result(**overrides: Any) -> MagicMock:
    mock_gepa_result = MagicMock()
    mock_gepa_result.history = []
    mock_gepa_result.pareto_front = []
    mock_gepa_result.total_metric_calls = 1
    for key, value in overrides.items():
        setattr(mock_gepa_result, key, value)
    return mock_gepa_result


def _run_optimize(
    monkeypatch,
    mock_optimization_context,
    simple_chat_prompt,
    mock_dataset,
    sample_dataset_items,
    sample_metric,
    *,
    gepa_result: MagicMock | None = None,
    **optimize_kwargs: Any,
) -> tuple[Any, dict[str, Any]]:
    """Run optimize_prompt with gepa.optimize mocked; return (result, captured kwargs)."""
    mock_optimization_context()

    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0, seed=42)
    dataset = mock_dataset(
        sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
    )
    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

    captured: dict[str, Any] = {}

    def fake_optimize(**kwargs: Any) -> MagicMock:
        captured.update(kwargs)
        return gepa_result if gepa_result is not None else _make_mock_gepa_result()

    monkeypatch.setattr("gepa.optimize", fake_optimize)

    result = optimizer.optimize_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=sample_metric,
        max_trials=2,
        n_samples=2,
        **optimize_kwargs,
    )
    return result, captured


class TestGepaStopCallbackWiring:
    def test_stop_callbacks_wired_by_default(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """gepa.optimize must receive a full-eval score threshold stopper and a stall stopper."""
        _, captured = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )

        stoppers = captured["stop_callbacks"]
        assert len(stoppers) == 2
        assert isinstance(stoppers[0], ScoreThresholdStopper)
        assert stoppers[0].threshold == GepaOptimizer(model="gpt-4o-mini").perfect_score
        assert isinstance(stoppers[1], NoImprovementStopper)
        assert stoppers[1].max_iterations_without_improvement == 10

    def test_no_improvement_stopper_disabled_with_zero(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        _, captured = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            no_improvement_iterations=0,
        )

        stoppers = captured["stop_callbacks"]
        assert len(stoppers) == 1
        assert isinstance(stoppers[0], ScoreThresholdStopper)

    def test_no_improvement_iterations_override(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        _, captured = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            no_improvement_iterations=3,
        )

        stoppers = captured["stop_callbacks"]
        assert isinstance(stoppers[1], NoImprovementStopper)
        assert stoppers[1].max_iterations_without_improvement == 3

    def test_budget_stopper_still_configured(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """stop_callbacks must compose with (not replace) the metric-call budget."""
        _, captured = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )
        assert captured["max_metric_calls"] is not None
        assert captured["max_metric_calls"] > 0


class TestGepaFinishReason:
    def test_perfect_full_eval_score_sets_finish_reason(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """A full-eval (valset) score at/above perfect_score is reported as an early stop."""
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.5, 1.0]
        )
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
        )

        assert result.details["finish_reason"] == "perfect_score"
        assert result.details["stopped_early"] is True

    def test_below_threshold_scores_do_not_mark_early_stop(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.3, 0.5]
        )
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
        )

        assert result.details["finish_reason"] != "perfect_score"


class TestStopperSemantics:
    """Guard the apples-to-apples property: stoppers read full-eval scores only."""

    def test_threshold_stopper_fires_on_full_eval_score(self) -> None:
        stopper = ScoreThresholdStopper(0.95)
        state = SimpleNamespace(program_full_scores_val_set=[0.5, 1.0])
        assert stopper(state) is True

    def test_threshold_stopper_ignores_missing_full_evals(self) -> None:
        """A perfect mini-batch never lands in program_full_scores_val_set,
        so an empty/low full-eval list must not stop the run."""
        stopper = ScoreThresholdStopper(0.95)
        assert stopper(SimpleNamespace(program_full_scores_val_set=[])) is False
        assert (
            stopper(SimpleNamespace(program_full_scores_val_set=[0.5, 0.9])) is False
        )

    def test_no_improvement_stopper_counts_stagnant_iterations(self) -> None:
        stopper = NoImprovementStopper(3)
        improving = SimpleNamespace(program_full_scores_val_set=[0.5])
        stagnant = SimpleNamespace(program_full_scores_val_set=[0.5])

        assert stopper(improving) is False  # first call establishes the best
        assert stopper(stagnant) is False  # 1 stagnant iteration
        assert stopper(stagnant) is False  # 2
        assert stopper(stagnant) is True  # 3 -> stop

    def test_no_improvement_stopper_resets_on_improvement(self) -> None:
        stopper = NoImprovementStopper(2)
        assert stopper(SimpleNamespace(program_full_scores_val_set=[0.5])) is False
        assert stopper(SimpleNamespace(program_full_scores_val_set=[0.5])) is False
        # improvement resets the counter
        assert stopper(SimpleNamespace(program_full_scores_val_set=[0.6])) is False
        assert stopper(SimpleNamespace(program_full_scores_val_set=[0.6])) is False
        assert stopper(SimpleNamespace(program_full_scores_val_set=[0.6])) is True
