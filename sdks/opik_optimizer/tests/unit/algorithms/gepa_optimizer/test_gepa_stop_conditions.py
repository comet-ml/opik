# mypy: disable-error-code=no-untyped-def

from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

from gepa.utils.stop_condition import NoImprovementStopper

from opik_optimizer import GepaOptimizer

_GEPA_OPTIMIZER_LOGGER = "opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer"


def _make_mock_gepa_result(**overrides: Any) -> MagicMock:
    mock_gepa_result = MagicMock()
    mock_gepa_result.history = []
    mock_gepa_result.pareto_front = []
    # The runs below use max_trials=2 * n_samples=2, so this stands for a spent
    # metric-call budget — the reason a real gepa run exits when none of our own
    # stoppers fired. Tests about an unspent budget override it explicitly.
    mock_gepa_result.total_metric_calls = 4
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
    on_gepa_optimize: Any = None,
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
        if on_gepa_optimize is not None:
            on_gepa_optimize(kwargs)
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

    def test_finish_reason__no_wired_stopper_fired__falls_back_to_max_trials(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """This unit covers the fallback LABELING only: gepa.optimize is mocked
        (its internal MaxMetricCallsStopper never runs), so the test asserts
        that a gepa exit with neither wired stopper fired and the metric-call
        budget spent is labeled 'max_trials' — never 'completed' (OPIK-7511).
        That the budget is the remaining exit path is a property of the gepa
        engine (it only ever exits via stop conditions); a real budget-driven
        exit is exercised end-to-end by the imperfect-baseline e2e run."""
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.3, 0.5], total_metric_calls=4
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

        assert result.details["finish_reason"] == "max_trials"
        assert result.details["stop_reason"] == "max_trials"

    def test_finish_reason__budget_unspent_with_run_dir__is_cancelled_not_max_trials(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
        tmp_path,
    ) -> None:
        """gepa installs its own FileStopper on <run_dir>/gepa.stop, so a run with
        run_dir set can exit with neither of our stoppers fired AND budget left on
        the clock. Labeling that 'max_trials' would report a budget burn that
        never happened; it is an external stop request."""
        # max_trials=2 * n_samples=2 = 4 metric calls of budget, 1 spent.
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.3, 0.5], total_metric_calls=1
        )
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
            run_dir=str(tmp_path),
        )

        assert result.details["finish_reason"] == "cancelled"

    def test_finish_reason__budget_unspent_without_run_dir__stays_max_trials(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Without run_dir gepa wires no FileStopper, so nothing can stop the run
        externally and an unspent-looking budget can only mean the counter and
        the stopper's threshold disagree (gepa is pinned open-ended). Guessing
        'cancelled' there would relabel ordinary budget exits — including every
        Optimization Studio run, which never sets run_dir."""
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.3, 0.5], total_metric_calls=1
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

        assert result.details["finish_reason"] == "max_trials"

    def test_finish_reason__unknown_spend__still_falls_back_to_max_trials(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
        tmp_path,
    ) -> None:
        """total_metric_calls is optional run metadata on GEPAResult. Even with a
        stop file watched, without the counter we cannot tell an external stop
        from a budget burn, so the label must stay on the old fallback rather
        than guess 'cancelled'."""
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.3, 0.5], total_metric_calls=None
        )
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
            run_dir=str(tmp_path),
        )

        assert result.details["finish_reason"] == "max_trials"

    def test_finish_reason__perfect_score_wins_over_unspent_budget(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
        tmp_path,
    ) -> None:
        """Reaching the target always leaves budget unspent — that must still
        read as 'perfect_score', not as an external stop. run_dir is set so the
        external-stop branch is live and precedence is actually exercised."""
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.5, 1.0], total_metric_calls=1
        )
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
            run_dir=str(tmp_path),
        )

        assert result.details["finish_reason"] == "perfect_score"

    def test_finish_reason__no_improvement_stall__reports_no_improvement(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """When the wired NoImprovementStopper trips, the run must report
        'no_improvement', not a budget burn."""

        def stall_the_stopper(kwargs: dict[str, Any]) -> None:
            # Drive the wired stopper the way the gepa engine would: one call
            # per iteration with a stagnant full-eval score until it trips.
            # Bounded so a stopper regression fails this test instead of
            # hanging the whole suite.
            stopper = next(
                s
                for s in kwargs["stop_callbacks"]
                if isinstance(s, NoImprovementStopper)
            )
            state = SimpleNamespace(program_full_scores_val_set=[0.5])
            limit = stopper.max_iterations_without_improvement + 2
            tripped = any(stopper(state) for _ in range(limit))
            assert tripped, (
                f"NoImprovementStopper did not trip within {limit} stagnant iterations"
            )

        gepa_result = _make_mock_gepa_result(candidates=[], val_aggregate_scores=[0.5])
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
            on_gepa_optimize=stall_the_stopper,
            no_improvement_iterations=3,
        )

        assert result.details["finish_reason"] == "no_improvement"


class TestCandidateScoreThresholdStopper:
    """OPIK-7511: one lucky eval of the SEED program must not end the run.

    With a coarse 0/1 metric the seed's own full eval is noisy (and unpinnable on
    the gpt-5 family), so stopping on it produced runs that reported
    finish_reason="perfect_score" while showing no improvement.
    """

    def test_finish_reason__only_seed_reached_threshold__is_not_perfect_score(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The label must agree with the stopper: a seed-only 1.0 is a budget
        exit, not a perfect score."""
        gepa_result = _make_mock_gepa_result(candidates=[], val_aggregate_scores=[1.0])
        result, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            gepa_result=gepa_result,
        )

        assert result.details["finish_reason"] == "max_trials"

    def test_finish_reason__non_finite_candidate_score__is_not_perfect_score(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The label filters non-finite scores exactly like the stopper, so an inf
        cannot claim a stop that never happened."""
        gepa_result = _make_mock_gepa_result(
            candidates=[], val_aggregate_scores=[0.5, float("inf")]
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

        assert result.details["finish_reason"] == "max_trials"
