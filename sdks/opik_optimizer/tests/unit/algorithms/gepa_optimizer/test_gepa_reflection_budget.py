# mypy: disable-error-code=no-untyped-def

import logging
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

from opik_optimizer import GepaOptimizer
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import (
    _coerce_max_reflection_calls,
    _ReflectionBudgetStopper,
    _resolve_gepa_finish_reason,
)


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
    fake_optimize_hook: Any = None,
    optimizer_kwargs: dict[str, Any] | None = None,
    **optimize_kwargs: Any,
) -> tuple[Any, dict[str, Any], GepaOptimizer]:
    """Run optimize_prompt with gepa.optimize mocked; return (result, kwargs, optimizer).

    fake_optimize_hook, when given, is called with the captured gepa kwargs
    before returning the mock result — used to simulate reflection-LM spend.
    """
    mock_optimization_context()

    optimizer = GepaOptimizer(
        model="gpt-4o-mini", verbose=0, seed=42, **(optimizer_kwargs or {})
    )
    dataset = mock_dataset(
        sample_dataset_items, name="test-dataset", dataset_id="dataset-123"
    )
    monkeypatch.setattr(optimizer, "evaluate_prompt", lambda **kwargs: 0.5)

    captured: dict[str, Any] = {}

    def fake_optimize(**kwargs: Any) -> MagicMock:
        captured.update(kwargs)
        if fake_optimize_hook is not None:
            fake_optimize_hook(kwargs)
        return _make_mock_gepa_result()

    monkeypatch.setattr("gepa.optimize", fake_optimize)

    result = optimizer.optimize_prompt(
        prompt=simple_chat_prompt,
        dataset=dataset,
        metric=sample_metric,
        max_trials=2,
        n_samples=2,
        **optimize_kwargs,
    )
    return result, captured, optimizer


def _patch_call_model(monkeypatch, response: str = "new instruction") -> list[dict]:
    """Replace core.llm_calls.call_model with a recorder; return the call log."""
    calls: list[dict] = []

    def fake_call_model(*args: Any, **kwargs: Any) -> str:
        calls.append(kwargs)
        return response

    monkeypatch.setattr("opik_optimizer.core.llm_calls.call_model", fake_call_model)
    return calls


class TestReflectionLmWiring:
    def test_reflection_lm_is_a_callable_not_the_model_string(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The bare model string would make gepa call litellm directly —
        untraced, uncounted, unbounded (OPIK-7521)."""
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )

        assert not isinstance(captured["reflection_lm"], str)
        assert callable(captured["reflection_lm"])

    def test_reflection_callable_counts_and_routes_through_call_model(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        calls = _patch_call_model(monkeypatch)
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            optimizer_kwargs={"model_parameters": {"temperature": 0.1}},
        )

        reflection_lm = captured["reflection_lm"]
        assert reflection_lm("improve this prompt") == "new instruction"
        assert reflection_lm("and again") == "new instruction"

        assert len(calls) == 2
        first = calls[0]
        assert first["messages"] == [{"role": "user", "content": "improve this prompt"}]
        assert first["model"] == "gpt-4o-mini"
        assert first["model_parameters"] == {"temperature": 0.1}
        assert first["metadata"]["opik_call_type"] == "reflection"
        assert first["metadata"]["optimizer_name"] == "GepaOptimizer"
        assert first["optimization_id"] == "test-opt-123"

    def test_reflection_callable_passes_message_lists_through(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """gepa may hand the callable a chat-message list instead of a string."""
        calls = _patch_call_model(monkeypatch)
        _, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
        )

        messages = [{"role": "system", "content": "reflect"}]
        captured["reflection_lm"](messages)
        assert calls[0]["messages"] == messages

    def test_reflection_callable_refuses_calls_beyond_the_budget(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The stopper only runs between engine iterations, so the callable
        itself must refuse to spend past max_reflection_calls mid-iteration."""
        calls = _patch_call_model(monkeypatch)

        def overspend(kwargs: dict[str, Any]) -> None:
            assert kwargs["reflection_lm"]("first proposal") == "new instruction"
            assert kwargs["reflection_lm"]("beyond the cap") == ""

        result, _, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            fake_optimize_hook=overspend,
            max_reflection_calls=1,
        )

        # Only the in-budget call reached the LLM or was counted.
        assert len(calls) == 1
        assert result.details["reflection_call_count"] == 1
        assert result.details["max_reflection_calls"] == 1
        assert result.details["finish_reason"] == "reflection_budget"

    def test_budget_refusal_warns_once_not_per_refused_call(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
        caplog,
    ) -> None:
        """A selector that keeps asking after the cap must not flood the logs:
        refusal is cheap and already reported via finish_reason, so one warning
        per run is enough to explain the outcome."""
        _patch_call_model(monkeypatch)

        def overspend(kwargs: dict[str, Any]) -> None:
            for proposal in ("in budget", "refused", "refused", "refused"):
                kwargs["reflection_lm"](proposal)

        with caplog.at_level(logging.WARNING):
            _run_optimize(
                monkeypatch,
                mock_optimization_context,
                simple_chat_prompt,
                mock_dataset,
                sample_dataset_items,
                sample_metric,
                fake_optimize_hook=overspend,
                max_reflection_calls=1,
            )

        refusals = [
            record
            for record in caplog.records
            if "beyond max_reflection_calls" in record.getMessage()
        ]
        assert len(refusals) == 1


class TestReflectionBudgetStopper:
    def test_stops_only_once_budget_is_spent(self) -> None:
        optimizer = SimpleNamespace(_reflection_call_count=0)
        stopper = _ReflectionBudgetStopper(optimizer, 3)  # type: ignore[arg-type]
        state = SimpleNamespace()

        assert stopper(state) is False
        optimizer._reflection_call_count = 2
        assert stopper(state) is False
        optimizer._reflection_call_count = 3
        assert stopper(state) is True
        optimizer._reflection_call_count = 4
        assert stopper(state) is True


class TestMaxReflectionCallsKnob:
    def test_override_via_extra_params(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        result, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            max_reflection_calls=5,
        )

        stoppers = [
            s
            for s in captured["stop_callbacks"]
            if isinstance(s, _ReflectionBudgetStopper)
        ]
        assert len(stoppers) == 1
        assert stoppers[0].max_reflection_calls == 5
        assert result.details["max_reflection_calls"] == 5

    def test_zero_disables_the_cap_but_count_is_still_reported(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        result, captured, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            max_reflection_calls=0,
        )

        assert not any(
            isinstance(s, _ReflectionBudgetStopper) for s in captured["stop_callbacks"]
        )
        assert result.details["max_reflection_calls"] == 0
        assert result.details["reflection_call_count"] == 0


class TestCoerceMaxReflectionCalls:
    def test_default_is_max_trials(self) -> None:
        assert _coerce_max_reflection_calls(None, max_trials=7) == 7

    def test_zero_disables(self) -> None:
        assert _coerce_max_reflection_calls(0, max_trials=7) == 0

    def test_numeric_string_parsed(self) -> None:
        assert _coerce_max_reflection_calls("3", max_trials=7) == 3

    def test_invalid_value_falls_back_to_max_trials(self) -> None:
        assert _coerce_max_reflection_calls("lots", max_trials=7) == 7

    def test_negative_disables(self) -> None:
        assert _coerce_max_reflection_calls(-1, max_trials=7) == 0


class TestReflectionBudgetFinishReason:
    # Index 0 of a gepa full-eval list is the seed program's own eval, which the
    # resolver excludes (OPIK-7511), so a candidate score goes in position 1.
    def test_resolves_reflection_budget_when_budget_spent(self) -> None:
        reason = _resolve_gepa_finish_reason(
            val_scores=[0.2, 0.3],
            perfect_score=0.95,
            no_improvement_stopper=None,
            no_improvement_iterations=0,
            total_metric_calls=4,
            max_metric_calls=4,
            stop_file_watched=False,
            reflection_calls=4,
            max_reflection_calls=4,
        )
        assert reason == "reflection_budget"

    def test_reflection_budget_is_not_reported_as_an_external_stop(self) -> None:
        """Reflection exhaustion also leaves metric-call budget unspent.

        The stop-file branch (OPIK-7511) reads an unspent metric budget as
        "cancelled", so with a run_dir watched the reflection cap has to be
        resolved first or a budget-capped run is mislabelled an external stop.
        """
        reason = _resolve_gepa_finish_reason(
            val_scores=[0.2, 0.3],
            perfect_score=0.95,
            no_improvement_stopper=None,
            no_improvement_iterations=0,
            total_metric_calls=1,
            max_metric_calls=4,
            stop_file_watched=True,
            reflection_calls=4,
            max_reflection_calls=4,
        )
        assert reason == "reflection_budget"

    def test_disabled_cap_never_resolves_reflection_budget(self) -> None:
        reason = _resolve_gepa_finish_reason(
            val_scores=[0.2, 0.3],
            perfect_score=0.95,
            no_improvement_stopper=None,
            no_improvement_iterations=0,
            total_metric_calls=4,
            max_metric_calls=4,
            stop_file_watched=False,
            reflection_calls=100,
            max_reflection_calls=0,
        )
        assert reason is None

    def test_perfect_score_takes_precedence(self) -> None:
        reason = _resolve_gepa_finish_reason(
            val_scores=[0.2, 1.0],
            perfect_score=0.95,
            no_improvement_stopper=None,
            no_improvement_iterations=0,
            total_metric_calls=4,
            max_metric_calls=4,
            stop_file_watched=False,
            reflection_calls=4,
            max_reflection_calls=4,
        )
        assert reason == "perfect_score"

    def test_no_improvement_takes_precedence(self) -> None:
        stopper = SimpleNamespace(
            iterations_without_improvement=10,
            max_iterations_without_improvement=10,
        )
        reason = _resolve_gepa_finish_reason(
            val_scores=[0.2, 0.3],
            perfect_score=0.95,
            no_improvement_stopper=stopper,
            no_improvement_iterations=10,
            total_metric_calls=4,
            max_metric_calls=4,
            stop_file_watched=False,
            reflection_calls=4,
            max_reflection_calls=4,
        )
        assert reason == "no_improvement"

    def test_end_to_end_reflection_budget_finish_reason_and_reporting(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """A run that spends its whole reflection budget reports it honestly:
        finish_reason, stopped_early, and the call count in details."""
        calls = _patch_call_model(monkeypatch)

        def spend_reflection_budget(kwargs: dict[str, Any]) -> None:
            # Simulate gepa's engine loop: stop callbacks run at the top of
            # each iteration, then the proposer makes one reflection call.
            state = SimpleNamespace(program_full_scores_val_set=[0.5])
            for proposal in ("first proposal", "second proposal", "third proposal"):
                if any(callback(state) for callback in kwargs["stop_callbacks"]):
                    break
                kwargs["reflection_lm"](proposal)

        result, _, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            fake_optimize_hook=spend_reflection_budget,
        )

        # max_trials=2 -> default budget of 2 reflection calls: the stopper
        # halts the loop before the third proposal is attempted.
        assert len(calls) == 2
        assert result.details["reflection_call_count"] == 2
        assert result.details["max_reflection_calls"] == 2
        assert result.details["finish_reason"] == "reflection_budget"
        assert result.details["stopped_early"] is True
