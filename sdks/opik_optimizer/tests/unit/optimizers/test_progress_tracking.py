# mypy: disable-error-code="no-untyped-def, no-untyped-call"
"""Tests for optimizer progress tracking and display functionality.

These tests verify that:
1. get_progress_state() returns correct state during optimization
2. _on_evaluation() is called with correct parameters
3. Progress tracking variables are updated correctly during optimization
4. The unified display system works across all optimizers
"""

import pytest
from unittest.mock import MagicMock
from typing import Any

from opik import Dataset

from opik_optimizer.base_optimizer import (
    BaseOptimizer,
    OptimizationContext,
    AlgorithmResult,
)
from opik_optimizer.api_objects.chat_prompt import ChatPrompt
from opik_optimizer.optimization_result import OptimizationResult
from opik_optimizer.algorithms.meta_prompt_optimizer import MetaPromptOptimizer


class ConcreteOptimizer(BaseOptimizer):
    """Concrete implementation for testing base class behavior."""

    def __init__(self, num_rounds: int = 3, **kwargs: Any) -> None:
        kwargs.setdefault("model", "gpt-4o-mini")
        super().__init__(**kwargs)
        self.num_rounds = num_rounds

    def run_optimization(self, context: OptimizationContext):
        prompts = context.prompts

        # Initialize progress tracking (as real optimizers do)
        self._current_round = 0
        self._total_rounds = self.num_rounds

        for i in range(self.num_rounds):
            # Update current round before evaluation
            self._current_round = i

            self.evaluate(prompts)
            if context.should_stop:
                break

        context.finish_reason = "completed"
        return AlgorithmResult(
            best_prompts=prompts,
            best_score=context.current_best_score or 0.0,
            history=[],
            metadata={"test": True},
        )

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        return {"optimizer": "ConcreteOptimizer"}


@pytest.fixture
def mock_dataset():
    """Create a mock dataset."""
    dataset = MagicMock(spec=Dataset)
    dataset.name = "test_dataset"
    dataset.id = "test-dataset-id"
    dataset.get_items.return_value = [
        {"id": "1", "input": "test1", "expected": "output1"},
        {"id": "2", "input": "test2", "expected": "output2"},
    ]
    return dataset


@pytest.fixture
def mock_metric():
    """Create a mock metric function."""

    def metric(dataset_item: dict, llm_output: str) -> float:
        return 0.8

    metric.__name__ = "test_metric"
    return metric


@pytest.fixture
def sample_prompt():
    """Create a sample ChatPrompt."""
    return ChatPrompt(messages=[{"role": "system", "content": "You are helpful."}])


@pytest.fixture
def mock_opik_client(monkeypatch):
    """Mock the Opik client to prevent API calls."""
    mock_client = MagicMock()
    mock_client.create_optimization.return_value = MagicMock(id="test-opt-id")
    monkeypatch.setattr("opik.Opik", lambda **kwargs: mock_client)
    return mock_client


class TestProgressTrackingDuringOptimization:
    """Tests that verify progress tracking during actual optimization runs."""

    def test_round_tracking_during_optimization(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that _current_round and _total_rounds are updated during optimization."""
        captured_rounds = []
        captured_total_rounds = []

        class RoundTrackingOptimizer(ConcreteOptimizer):
            def run_optimization(self, context: OptimizationContext) -> AlgorithmResult:
                prompts = context.prompts

                # Initialize progress tracking
                self._current_round = 0
                self._total_rounds = 5

                for i in range(5):
                    self._current_round = i
                    # Capture the round values during optimization
                    captured_rounds.append(self._current_round)
                    captured_total_rounds.append(self._total_rounds)

                    self.evaluate(prompts)
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return AlgorithmResult(
                    best_prompts=prompts,
                    best_score=context.current_best_score or 0.0,
                    history=[],
                    metadata={"test": True},
                )

        optimizer = RoundTrackingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Verify rounds were tracked correctly
        assert captured_rounds == [0, 1, 2, 3, 4]
        assert all(tr == 5 for tr in captured_total_rounds)

    def test_progress_state_reflects_current_round_during_optimization(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that get_progress_state() returns correct round during optimization."""
        captured_states = []

        class StateTrackingOptimizer(ConcreteOptimizer):
            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts

                self._current_round = 0
                self._total_rounds = 4

                for i in range(4):
                    self._current_round = i
                    self.evaluate(prompts)
                    # Capture the full progress state after each evaluation
                    captured_states.append(self.get_progress_state().copy())
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = StateTrackingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Verify progress state reflects correct rounds (1-based for display)
        assert len(captured_states) == 4
        assert captured_states[0]["round"] == 1  # _current_round=0 -> round=1
        assert captured_states[1]["round"] == 2  # _current_round=1 -> round=2
        assert captured_states[2]["round"] == 3  # _current_round=2 -> round=3
        assert captured_states[3]["round"] == 4  # _current_round=3 -> round=4

        # All should have same total_rounds
        for state in captured_states:
            assert state["total_rounds"] == 4

    def test_on_evaluation_receives_correct_round_info(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that _on_evaluation can access correct round info via get_progress_state."""
        on_eval_rounds = []

        class EvalTrackingOptimizer(ConcreteOptimizer):
            def _on_evaluation(self, context, prompts, score):
                # Capture round info when _on_evaluation is called
                state = self.get_progress_state()
                on_eval_rounds.append(state["round"])

            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts

                self._current_round = 0
                self._total_rounds = 3

                for i in range(3):
                    self._current_round = i
                    self.evaluate(prompts)  # This calls _on_evaluation
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = EvalTrackingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # _on_evaluation should have seen rounds 1, 2, 3 (1-based)
        assert on_eval_rounds == [1, 2, 3]


class TestMetaPromptOptimizerProgressTracking:
    """Tests for MetaPromptOptimizer progress tracking."""

    def test_progress_tracking_variables_initialized(self):
        """Test that progress tracking variables are initialized in __init__."""
        optimizer = MetaPromptOptimizer(verbose=0)

        assert hasattr(optimizer, "_trials_completed")
        assert hasattr(optimizer, "_rounds_completed")
        assert hasattr(optimizer, "_current_round")
        assert hasattr(optimizer, "_current_candidate")
        assert hasattr(optimizer, "_total_candidates_in_round")

        assert optimizer._trials_completed == 0
        assert optimizer._rounds_completed == 0
        assert optimizer._current_round == 0
        assert optimizer._current_candidate == 0
        assert optimizer._total_candidates_in_round == 0


class TestBaseOptimizerProgressState:
    """Tests for BaseOptimizer.get_progress_state()."""

    def test_get_progress_state_returns_trials_and_score(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that get_progress_state returns trials_completed and best_score during optimization."""
        captured_state = []

        class StateCapturingOptimizer(ConcreteOptimizer):
            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts
                for i in range(3):
                    self.evaluate(prompts)
                    # Capture state during optimization when context is set
                    captured_state.append(self.get_progress_state().copy())
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = StateCapturingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Check the captured state during optimization
        assert len(captured_state) == 3
        for state in captured_state:
            assert "trials_completed" in state
            assert "best_score" in state
            assert state["trials_completed"] >= 1
            assert state["best_score"] is not None

    def test_get_progress_state_during_optimization(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that get_progress_state is updated during optimization."""
        captured_states = []

        class StateCapturingOptimizer(ConcreteOptimizer):
            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts
                for i in range(3):
                    self.evaluate(prompts)
                    # Capture state after each evaluation
                    captured_states.append(self.get_progress_state().copy())
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = StateCapturingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Should have captured 3 states (one per evaluation)
        assert len(captured_states) == 3

        # trials_completed should increment
        assert captured_states[0]["trials_completed"] == 1
        assert captured_states[1]["trials_completed"] == 2
        assert captured_states[2]["trials_completed"] == 3


class TestOnEvaluationHook:
    """Tests for the _on_evaluation hook."""

    def test_on_evaluation_called_after_each_evaluate(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that _on_evaluation is called after each self.evaluate() call."""
        on_evaluation_calls = []

        class TrackingOptimizer(ConcreteOptimizer):
            def _on_evaluation(self, context, prompts, score):
                on_evaluation_calls.append(
                    {
                        "trials_completed": context.trials_completed,
                        "score": score,
                        "prompts": prompts,
                    }
                )

        optimizer = TrackingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.8
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Should have 3 calls (from ConcreteOptimizer's loop)
        assert len(on_evaluation_calls) == 3

        # Each call should have incrementing trials
        assert on_evaluation_calls[0]["trials_completed"] == 1
        assert on_evaluation_calls[1]["trials_completed"] == 2
        assert on_evaluation_calls[2]["trials_completed"] == 3

        # All calls should have the same score (mocked to 0.8)
        for call_info in on_evaluation_calls:
            assert call_info["score"] == 0.8

    def test_on_evaluation_receives_correct_prompts(
        self, mock_dataset, mock_metric, mock_opik_client, monkeypatch
    ):
        """Test that _on_evaluation receives the prompts that were evaluated."""
        received_prompts = []

        class TrackingOptimizer(ConcreteOptimizer):
            def _on_evaluation(self, context, prompts, score):
                received_prompts.append(prompts)

        optimizer = TrackingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.8
        )

        prompt = ChatPrompt(messages=[{"role": "system", "content": "Test prompt"}])

        optimizer.optimize_prompt(
            prompt=prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # All received prompts should be dicts with the prompt
        for prompts in received_prompts:
            assert isinstance(prompts, dict)
            assert len(prompts) == 1
            prompt_obj = list(prompts.values())[0]
            assert prompt_obj.get_messages()[0]["content"] == "Test prompt"


class TestDisplayEvaluationProgress:
    """Tests for the display_evaluation_progress function."""

    def test_display_evaluation_progress_called_in_on_evaluation(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that display_evaluation_progress is called from _on_evaluation."""
        display_calls = []

        def mock_display_evaluation_progress(**kwargs):
            display_calls.append(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.reporting_utils.display_evaluation_progress",
            mock_display_evaluation_progress,
        )
        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer = ConcreteOptimizer(verbose=1)  # verbose=1 to enable display

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Should have 3 display calls (one per evaluation in ConcreteOptimizer)
        assert len(display_calls) == 3

        # Each call should have the required parameters
        for call_kwargs in display_calls:
            assert "prefix" in call_kwargs
            assert "score_text" in call_kwargs
            assert "style" in call_kwargs
            assert "prompts" in call_kwargs
            assert "verbose" in call_kwargs

    def test_display_not_called_when_verbose_zero(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that display is not called when verbose=0."""
        display_calls = []

        def mock_display_evaluation_progress(**kwargs):
            display_calls.append(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.reporting_utils.display_evaluation_progress",
            mock_display_evaluation_progress,
        )
        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer = ConcreteOptimizer(verbose=0)  # verbose=0 to disable display

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Should have no display calls
        assert len(display_calls) == 0

    def test_display_includes_dataset_info(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that display includes dataset name and type."""
        display_calls = []

        def mock_display_evaluation_progress(**kwargs):
            display_calls.append(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.reporting_utils.display_evaluation_progress",
            mock_display_evaluation_progress,
        )
        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        # Set a name on the mock dataset
        mock_dataset.name = "test_dataset"

        optimizer = ConcreteOptimizer(verbose=1)

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Check that dataset info is passed to display
        assert len(display_calls) >= 1
        for call_kwargs in display_calls:
            assert "dataset_name" in call_kwargs
            assert "dataset_type" in call_kwargs
            assert call_kwargs["dataset_name"] == "test_dataset"
            assert call_kwargs["dataset_type"] == "training"

    def test_display_shows_validation_dataset_type(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that display shows 'validation' when validation dataset is used."""
        display_calls = []

        def mock_display_evaluation_progress(**kwargs):
            display_calls.append(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.reporting_utils.display_evaluation_progress",
            mock_display_evaluation_progress,
        )
        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        # Create a separate validation dataset using MagicMock without spec
        # to allow setting __iter__
        validation_dataset = MagicMock()
        validation_dataset.name = "validation_dataset"
        validation_dataset.__iter__ = MagicMock(return_value=iter([]))

        mock_dataset.name = "training_dataset"

        optimizer = ConcreteOptimizer(verbose=1)

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
            validation_dataset=validation_dataset,
        )

        # Check that validation dataset info is passed
        assert len(display_calls) >= 1
        for call_kwargs in display_calls:
            assert call_kwargs["dataset_name"] == "validation_dataset"
            assert call_kwargs["dataset_type"] == "validation"

    def test_display_includes_evaluation_settings(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that display includes evaluation settings when provided by optimizer."""
        display_calls = []

        def mock_display_evaluation_progress(**kwargs):
            display_calls.append(kwargs)

        monkeypatch.setattr(
            "opik_optimizer.reporting_utils.display_evaluation_progress",
            mock_display_evaluation_progress,
        )
        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        class OptimizerWithSettings(ConcreteOptimizer):
            def get_evaluation_display_info(self):
                info = super().get_evaluation_display_info()
                info["evaluation_settings"] = "n_samples=100, temperature=0.7"
                return info

        mock_dataset.name = "test_dataset"
        optimizer = OptimizerWithSettings(verbose=1)

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Check that evaluation settings are passed
        assert len(display_calls) >= 1
        for call_kwargs in display_calls:
            assert "evaluation_settings" in call_kwargs
            assert (
                call_kwargs["evaluation_settings"] == "n_samples=100, temperature=0.7"
            )


class TestDisplayEvaluationProgressFunction:
    """Tests for the display_evaluation_progress function in reporting_utils."""

    def test_display_function_accepts_dataset_params(self):
        """Test that display_evaluation_progress accepts dataset parameters."""
        from opik_optimizer import reporting_utils
        from opik_optimizer.api_objects.chat_prompt import ChatPrompt

        prompt = ChatPrompt(messages=[{"role": "system", "content": "Test"}])

        # Should not raise - function should accept these parameters
        reporting_utils.display_evaluation_progress(
            prefix="Round 1/5 - Candidate 1/10",
            score_text="0.8500",
            style="green",
            prompts={"main": prompt},
            verbose=0,  # verbose=0 so it doesn't actually print
            dataset_name="my_dataset",
            dataset_type="training",
            evaluation_settings="n_samples=50",
        )

    def test_display_function_accepts_validation_type(self):
        """Test that display_evaluation_progress accepts validation dataset type."""
        from opik_optimizer import reporting_utils
        from opik_optimizer.api_objects.chat_prompt import ChatPrompt

        prompt = ChatPrompt(messages=[{"role": "system", "content": "Test"}])

        # Should not raise - function should accept validation type
        reporting_utils.display_evaluation_progress(
            prefix="Round 1/5",
            score_text="0.75",
            style="green",
            prompts={"main": prompt},
            verbose=0,
            dataset_name="val_dataset",
            dataset_type="validation",
        )

    def test_display_function_accepts_none_dataset(self):
        """Test that display function handles None dataset name gracefully."""
        from opik_optimizer import reporting_utils
        from opik_optimizer.api_objects.chat_prompt import ChatPrompt

        prompt = ChatPrompt(messages=[{"role": "system", "content": "Test"}])

        # Should not raise - function should handle None values
        reporting_utils.display_evaluation_progress(
            prefix="Round 1/5",
            score_text="0.75",
            style="green",
            prompts={"main": prompt},
            verbose=0,
            dataset_name=None,
            dataset_type=None,
            evaluation_settings=None,
        )


class TestProgressTrackingIntegration:
    """Integration tests for progress tracking across the optimization lifecycle."""

    def test_trials_completed_increments_correctly(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that trials_completed increments correctly during optimization."""
        trials_at_each_step = []

        class TrackingOptimizer(ConcreteOptimizer):
            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts
                for i in range(5):
                    self.evaluate(prompts)
                    trials_at_each_step.append(context.trials_completed)
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = TrackingOptimizer(verbose=0)

        monkeypatch.setattr(
            "opik_optimizer.task_evaluator.evaluate", lambda **kwargs: 0.75
        )

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # trials_completed should increment by 1 each time
        assert trials_at_each_step == [1, 2, 3, 4, 5]

    def test_best_score_updated_correctly(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that best_score is updated correctly when a better score is found."""
        # Note: First score (0.5) is used for baseline calculation
        # So the optimization loop sees: 0.7, 0.6, 0.9, 0.8, 0.5
        scores_returned = [0.5, 0.7, 0.6, 0.9, 0.8, 0.5, 0.5, 0.5]  # Extra for safety
        score_index = [0]
        best_scores_at_each_step = []

        def mock_evaluate(**kwargs):
            if score_index[0] >= len(scores_returned):
                return 0.5  # Default score if we run out
            score = scores_returned[score_index[0]]
            score_index[0] += 1
            return score

        class TrackingOptimizer(ConcreteOptimizer):
            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts
                for i in range(5):
                    self.evaluate(prompts)
                    best_scores_at_each_step.append(context.current_best_score)
                    if context.should_stop:
                        break

                context.finish_reason = "completed"
                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = TrackingOptimizer(verbose=0)

        monkeypatch.setattr("opik_optimizer.task_evaluator.evaluate", mock_evaluate)

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # First score (0.5) is used for baseline, then optimization loop gets: 0.7, 0.6, 0.9, 0.8, 0.5
        # After each evaluate, best_score is updated:
        # eval 0.7 -> best = max(baseline=0.5, 0.7) = 0.7
        # eval 0.6 -> best = max(0.7, 0.6) = 0.7
        # eval 0.9 -> best = max(0.7, 0.9) = 0.9
        # eval 0.8 -> best = max(0.9, 0.8) = 0.9
        # eval 0.5 -> best = max(0.9, 0.5) = 0.9
        assert best_scores_at_each_step == [0.7, 0.7, 0.9, 0.9, 0.9]

    def test_early_stop_on_perfect_score_updates_context(
        self, mock_dataset, mock_metric, sample_prompt, mock_opik_client, monkeypatch
    ):
        """Test that early stop on perfect score updates context correctly."""
        evaluations_done: list[int] = [0]
        should_stop_when_stopped: list[bool | None] = [None]
        finish_reason_when_stopped: list[str | None] = [None]

        def mock_evaluate(**kwargs):
            evaluations_done[0] += 1
            # Return perfect score on 4th evaluation (1 for baseline + 3 in loop)
            return 0.99 if evaluations_done[0] == 4 else 0.5

        class TrackingOptimizer(ConcreteOptimizer):
            def run_optimization(
                self, context: OptimizationContext
            ) -> OptimizationResult:
                prompts = context.prompts
                for i in range(10):
                    self.evaluate(prompts)
                    if context.should_stop:
                        # Capture context state when stopped
                        should_stop_when_stopped[0] = context.should_stop
                        finish_reason_when_stopped[0] = context.finish_reason
                        break

                return OptimizationResult(
                    prompt=list(prompts.values())[0],
                    score=context.current_best_score or 0.0,
                    metric_name=context.metric.__name__,
                    history=[],
                    details={"test": True},
                )

        optimizer = TrackingOptimizer(
            verbose=0,
            skip_perfect_score=True,
            perfect_score=0.95,
        )

        monkeypatch.setattr("opik_optimizer.task_evaluator.evaluate", mock_evaluate)

        optimizer.optimize_prompt(
            prompt=sample_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            max_trials=10,
        )

        # Should have stopped after 4 evaluations (1 baseline + 3 in loop)
        assert evaluations_done[0] == 4
        assert should_stop_when_stopped[0] is True
        assert finish_reason_when_stopped[0] == "perfect_score"
