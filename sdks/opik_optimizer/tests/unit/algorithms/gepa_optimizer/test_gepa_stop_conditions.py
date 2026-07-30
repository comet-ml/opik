# mypy: disable-error-code=no-untyped-def

import logging
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

from gepa.utils.stop_condition import NoImprovementStopper, ScoreThresholdStopper

from opik_optimizer import GepaOptimizer, constants
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import (
    MIN_EXPECTED_REFLECTION_ITERATIONS,
    CandidateScoreThresholdStopper,
    _build_gepa_stop_callbacks,
    _coerce_no_improvement_iterations,
    _coerce_positive_int,
    _warn_if_reflection_minibatch_exhausts_budget,
)

_GEPA_OPTIMIZER_LOGGER = "opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer"


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
        that a gepa exit with neither wired stopper fired is labeled
        'max_trials' — never 'completed' (OPIK-7511). The claim that budget
        exhaustion is the only remaining exit path is a property of the gepa
        engine (it only ever exits via stop conditions); a real budget-driven
        exit is exercised end-to-end by the imperfect-baseline e2e run."""
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

        assert result.details["finish_reason"] == "max_trials"
        assert result.details["stop_reason"] == "max_trials"

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

    def test_stopper__only_seed_at_threshold__does_not_stop(self) -> None:
        stopper = CandidateScoreThresholdStopper(1.0)
        state = SimpleNamespace(program_full_scores_val_set=[1.0])
        assert stopper(state) is False

    def test_stopper__candidate_at_threshold__stops(self) -> None:
        stopper = CandidateScoreThresholdStopper(1.0)
        state = SimpleNamespace(program_full_scores_val_set=[0.95, 1.0])
        assert stopper(state) is True

    def test_stopper__candidates_below_threshold__does_not_stop(self) -> None:
        stopper = CandidateScoreThresholdStopper(1.0)
        state = SimpleNamespace(program_full_scores_val_set=[1.0, 0.9, 0.95])
        assert stopper(state) is False

    def test_stopper__empty_or_missing_history__does_not_stop(self) -> None:
        stopper = CandidateScoreThresholdStopper(0.95)
        assert stopper(SimpleNamespace(program_full_scores_val_set=[])) is False
        assert stopper(SimpleNamespace(program_full_scores_val_set=None)) is False
        assert stopper(SimpleNamespace()) is False

    def test_stopper__none_scores_are_skipped(self) -> None:
        stopper = CandidateScoreThresholdStopper(0.95)
        state = SimpleNamespace(program_full_scores_val_set=[0.5, None, 0.99])
        assert stopper(state) is True

    def test_stopper__non_finite_scores_do_not_trip_it(self) -> None:
        """A custom metric returning inf must not read as "target reached"."""
        stopper = CandidateScoreThresholdStopper(1.0)
        state = SimpleNamespace(
            program_full_scores_val_set=[0.5, float("inf"), float("nan"), 0.9]
        )
        assert stopper(state) is False

    def test_stopper__non_numeric_scores_are_skipped(self) -> None:
        stopper = CandidateScoreThresholdStopper(0.95)
        state = SimpleNamespace(program_full_scores_val_set=[0.5, "1.0", True, None])
        assert stopper(state) is False

    def test_stopper__unreadable_state__keeps_run_alive_but_warns(self, caplog) -> None:
        """A stop callback must never take a run down — but swallowing the failure
        silently would disable threshold stopping invisibly, so it logs."""

        class Boom:
            @property
            def program_full_scores_val_set(self) -> Any:
                raise RuntimeError("boom")

        with caplog.at_level(logging.WARNING, logger=_GEPA_OPTIMIZER_LOGGER):
            assert CandidateScoreThresholdStopper(0.95)(Boom()) is False
        assert "threshold stopping is inactive" in caplog.text

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
        assert stopper(SimpleNamespace(program_full_scores_val_set=[0.5, 0.9])) is False

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


class TestCoerceNoImprovementIterations:
    """no_improvement_iterations comes from user extra_params (Any) — validate it."""

    def test_none_falls_back_to_default(self) -> None:
        assert (
            _coerce_no_improvement_iterations(None)
            == constants.DEFAULT_GEPA_NO_IMPROVEMENT_ITERATIONS
        )

    def test_valid_int_passthrough(self) -> None:
        assert _coerce_no_improvement_iterations(7) == 7

    def test_zero_disables(self) -> None:
        assert _coerce_no_improvement_iterations(0) == 0

    def test_float_rounds_down_not_silently(self) -> None:
        # 2.5 must not be silently truncated to a surprising value; we floor it.
        assert _coerce_no_improvement_iterations(2.5) == 2

    def test_numeric_string_parsed(self) -> None:
        assert _coerce_no_improvement_iterations("3") == 3

    def test_invalid_string_falls_back_to_default(self) -> None:
        assert (
            _coerce_no_improvement_iterations("soon")
            == constants.DEFAULT_GEPA_NO_IMPROVEMENT_ITERATIONS
        )

    def test_negative_disables(self) -> None:
        assert _coerce_no_improvement_iterations(-5) == 0


class TestCoercePositiveInt:
    """The generalized boundary coercion used for both stall + minibatch knobs."""

    def test_allow_zero_false_below_min_uses_default(self) -> None:
        # reflection_minibatch_size semantics: 0 is invalid, fall back to default.
        assert _coerce_positive_int(0, default=3, allow_zero=False, name="mb") == 3
        assert _coerce_positive_int(-1, default=3, allow_zero=False, name="mb") == 3

    def test_allow_zero_true_keeps_zero(self) -> None:
        assert _coerce_positive_int(0, default=10, allow_zero=True, name="n") == 0

    def test_none_uses_default(self) -> None:
        assert _coerce_positive_int(None, default=3, allow_zero=False, name="mb") == 3

    def test_string_does_not_crash_and_uses_default(self) -> None:
        # The reflection-minibatch crash Baz flagged: a string must warn, not raise.
        assert _coerce_positive_int("big", default=3, allow_zero=False, name="mb") == 3

    def test_dict_input_does_not_crash(self) -> None:
        # A dict/list must not raise nor be dumped raw into logs.
        assert (
            _coerce_positive_int({"a": 1}, default=3, allow_zero=False, name="mb") == 3
        )

    def test_float_floored(self) -> None:
        assert _coerce_positive_int(4.9, default=3, allow_zero=False, name="mb") == 4

    def test_infinity_does_not_crash(self) -> None:
        # int(float("inf")) raises OverflowError — must be caught, not propagated.
        assert (
            _coerce_positive_int(float("inf"), default=3, allow_zero=False, name="mb")
            == 3
        )

    def test_nan_does_not_crash(self) -> None:
        assert (
            _coerce_positive_int(float("nan"), default=7, allow_zero=True, name="n")
            == 7
        )


class TestReflectionMinibatchBudgetWarning:
    """The warning must state the REAL cost model: a large mini-batch doesn't
    stop reflection, it exhausts the metric budget in few iterations."""

    def test_budget_warning__too_few_iterations_fit__logs_real_iteration_count(
        self, caplog
    ) -> None:
        # 400 // (2 * 50) = 4 iterations — below the expected minimum of 5.
        with caplog.at_level(logging.WARNING, logger=_GEPA_OPTIMIZER_LOGGER):
            _warn_if_reflection_minibatch_exhausts_budget(
                reflection_minibatch_size=50,
                max_metric_calls=400,
            )
        assert "allows only ~4 iteration(s)" in caplog.text
        assert "~100 metric calls per reflection iteration" in caplog.text

    def test_budget_warning__ample_budget__stays_silent(self, caplog) -> None:
        # 400 // (2 * 5) = 40 iterations — plenty; must not cry wolf.
        with caplog.at_level(logging.WARNING, logger=_GEPA_OPTIMIZER_LOGGER):
            _warn_if_reflection_minibatch_exhausts_budget(
                reflection_minibatch_size=5,
                max_metric_calls=400,
            )
        assert caplog.text == ""

    def test_budget_warning__exactly_min_iterations__stays_silent(self, caplog) -> None:
        # 400 // (2 * 40) = 5 == MIN_EXPECTED_REFLECTION_ITERATIONS.
        assert MIN_EXPECTED_REFLECTION_ITERATIONS == 5
        with caplog.at_level(logging.WARNING, logger=_GEPA_OPTIMIZER_LOGGER):
            _warn_if_reflection_minibatch_exhausts_budget(
                reflection_minibatch_size=40,
                max_metric_calls=400,
            )
        assert caplog.text == ""


class TestBuildGepaStopCallbacks:
    """Direct unit tests for the stop-callback builder (review hardening)."""

    def test_positive_perfect_score_wires_threshold_stopper(self) -> None:
        stoppers, _ = _build_gepa_stop_callbacks(0.95, 10)
        assert any(isinstance(s, ScoreThresholdStopper) for s in stoppers)
        assert any(isinstance(s, NoImprovementStopper) for s in stoppers)

    def test_nonpositive_perfect_score_omits_threshold_stopper(self) -> None:
        # perfect_score=0 would fire on the first full eval (score >= 0) and halt
        # the run immediately, so the threshold stopper must not be wired.
        stoppers, _ = _build_gepa_stop_callbacks(0.0, 10)
        assert not any(isinstance(s, ScoreThresholdStopper) for s in stoppers)
        assert any(isinstance(s, NoImprovementStopper) for s in stoppers)

    def test_empty_callbacks_when_both_disabled(self) -> None:
        # No threshold + no stall stopper → empty list; gepa still stops on the
        # metric-call budget.
        stoppers, no_improve = _build_gepa_stop_callbacks(0.0, 0)
        assert stoppers == []
        assert no_improve is None
