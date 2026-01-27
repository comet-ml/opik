"""Unit tests for BaseOptimizer history lifecycle helpers."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from unittest.mock import MagicMock

from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset, make_optimization_context


class TestHistoryManagement:
    """Tests for history management methods."""

    def test_history_starts_empty(self) -> None:
        """History should start empty."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.get_history_entries() == []

    def test_round_lifecycle_adds_round_data(self, simple_chat_prompt) -> None:
        """start/record/end round should add round data via the history state."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        context = MagicMock()
        context.dataset = MagicMock()
        context.dataset.name = "train-x"
        context.evaluation_dataset = MagicMock()
        context.evaluation_dataset.name = "dataset-x"
        context.dataset_split = "validation"

        handle = optimizer.pre_round(context)
        optimizer.post_trial(
            context,
            simple_chat_prompt,
            score=0.5,
            trial_index=1,
            round_handle=handle,
        )
        optimizer.post_round(
            round_handle=handle,
            context=context,
            best_score=0.5,
            best_candidate=simple_chat_prompt,
            extras={"improvement": 0.0},
        )

        history = optimizer.get_history_entries()
        assert len(history) == 1
        assert history[0]["round_index"] == 0
        assert history[0]["trials"][0]["dataset"] == "dataset-x"
        assert history[0]["trials"][0]["dataset_split"] == "validation"
        assert history[0]["extra"]["training_dataset"] == "train-x"
        assert history[0]["extra"]["evaluation_dataset"] == "dataset-x"

    def test_cleanup_clears_history(self, simple_chat_prompt) -> None:
        """cleanup should clear the history."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        context = MagicMock()

        handle = optimizer.pre_round(context)
        optimizer.post_trial(
            context,
            simple_chat_prompt,
            score=0.5,
            trial_index=1,
            round_handle=handle,
        )
        optimizer.post_round(
            round_handle=handle,
            best_score=0.5,
            best_candidate=simple_chat_prompt,
            extras={"improvement": 0.0},
        )

        optimizer.cleanup()

        assert optimizer.get_history_entries() == []


def test_post_round_infers_stop_reason_with_context(simple_chat_prompt) -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=1,
    )
    context.should_stop = True
    context.finish_reason = "max_trials"

    round_handle = optimizer.pre_round(context)
    optimizer.post_round(round_handle, context=context)

    entries = optimizer.get_history_entries()
    assert entries[-1]["stop_reason"] == "max_trials"
    assert entries[-1]["stopped"] is True


def test_post_round_defaults_without_context(simple_chat_prompt) -> None:
    optimizer = ConcreteOptimizer(model="gpt-4")
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=1,
    )
    context.should_stop = True
    context.finish_reason = "max_trials"

    round_handle = optimizer.pre_round(context)
    optimizer.post_round(round_handle)

    entries = optimizer.get_history_entries()
    assert entries[-1]["stop_reason"] == "completed"
    assert entries[-1]["stopped"] is False
