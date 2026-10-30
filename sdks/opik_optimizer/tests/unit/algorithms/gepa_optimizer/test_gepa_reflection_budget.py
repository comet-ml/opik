# mypy: disable-error-code=no-untyped-def

import threading
from types import SimpleNamespace
from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer import GepaOptimizer
from opik_optimizer.core.exceptions import ReflectionBudgetExceededError
from opik_optimizer.algorithms.gepa_optimizer.gepa_optimizer import (
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
            # Refusal RAISES: returning "" would make gepa's output_extractor
            # build a candidate whose prompt is empty and evaluate it for real.
            with pytest.raises(ReflectionBudgetExceededError):
                kwargs["reflection_lm"]("beyond the cap")
            # Then gepa reaches its next stop check and the stopper ends the run,
            # which is what makes the finish reason below "reflection_budget".
            state = SimpleNamespace(program_full_scores_val_set=[0.5])
            assert any(callback(state) for callback in kwargs["stop_callbacks"])

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

    def test_concurrent_callers_cannot_overshoot_the_budget(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """Claiming a slot is atomic: callers racing for the last one must not
        all read the counter below the cap and all spend."""
        calls = _patch_call_model(monkeypatch)
        thread_count = 8
        barrier = threading.Barrier(thread_count)
        responses: list[str] = []
        responses_lock = threading.Lock()

        def race(kwargs: dict[str, Any]) -> None:
            reflection_lm = kwargs["reflection_lm"]

            def worker() -> None:
                barrier.wait(timeout=10)
                try:
                    response = reflection_lm("proposal")
                except ReflectionBudgetExceededError:
                    response = "refused"
                with responses_lock:
                    responses.append(response)

            threads = [
                threading.Thread(target=worker, name=f"reflection-{index}")
                for index in range(thread_count)
            ]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(timeout=10)

        result, _, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            fake_optimize_hook=race,
            max_reflection_calls=2,
        )

        assert len(responses) == thread_count
        # Exactly the budget was spent; every other caller was refused.
        assert len(calls) == 2
        assert len([r for r in responses if r == "new instruction"]) == 2
        assert result.details["reflection_call_count"] == 2


class TestReflectionBudgetFinishReason:
    # Index 0 of a gepa full-eval list is the seed program's own eval, which the
    # resolver excludes (OPIK-7511), so a candidate score goes in position 1.

    def test_perfect_score_takes_precedence(self) -> None:
        reason = _resolve_gepa_finish_reason(
            val_scores=[0.2, 1.0],
            perfect_score=0.95,
            no_improvement_stopper=None,
            no_improvement_iterations=0,
            total_metric_calls=1,
            max_metric_calls=4,
            stop_file_watched=False,
            reflection_budget_exhausted=True,
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
            total_metric_calls=1,
            max_metric_calls=4,
            stop_file_watched=False,
            reflection_budget_exhausted=True,
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
            max_reflection_calls=2,
        )

        # The stopper halts the loop before the third proposal is attempted.
        assert len(calls) == 2
        assert result.details["reflection_call_count"] == 2
        assert result.details["max_reflection_calls"] == 2
        assert result.details["finish_reason"] == "reflection_budget"
        assert result.details["stopped_early"] is True

    def test_default_run_is_uncapped_and_never_reports_reflection_budget(
        self,
        mock_optimization_context,
        monkeypatch,
        simple_chat_prompt,
        mock_dataset,
        sample_dataset_items,
        sample_metric,
    ) -> None:
        """The knob is opt-in: with no max_reflection_calls, GEPA reflects as
        often as its own search wants and the run is never labelled as stopped
        by a reflection budget. Pinning this keeps the default from silently
        shortening searches — reflection calls scale with engine iterations, not
        with max_trials, so a max_trials default would truncate ordinary runs."""
        calls = _patch_call_model(monkeypatch)

        def reflect_more_often_than_max_trials(kwargs: dict[str, Any]) -> None:
            state = SimpleNamespace(program_full_scores_val_set=[0.5])
            # max_trials=2, but a real run reflects far more often than that.
            for index in range(8):
                assert not any(cb(state) for cb in kwargs["stop_callbacks"])
                kwargs["reflection_lm"](f"proposal {index}")

        result, _, _ = _run_optimize(
            monkeypatch,
            mock_optimization_context,
            simple_chat_prompt,
            mock_dataset,
            sample_dataset_items,
            sample_metric,
            fake_optimize_hook=reflect_more_often_than_max_trials,
        )

        assert len(calls) == 8
        assert result.details["reflection_call_count"] == 8
        assert result.details["max_reflection_calls"] == 0
        assert result.details["finish_reason"] != "reflection_budget"
