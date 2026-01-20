"""Unit tests for BaseOptimizer framework behaviors (counters, history, helpers)."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any, cast
from unittest.mock import MagicMock

import pytest

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.core.state import OptimizationContext
from opik_optimizer.utils import tool_helpers as tool_utils
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset, make_optimization_context


class TestCounterManagement:
    """Tests for counter management methods."""

    def test_counters_start_at_zero(self) -> None:
        """Counters should start at zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0

    def test_increment_llm_counter(self) -> None:
        """_increment_llm_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_counter()
        optimizer._increment_llm_counter()

        assert optimizer.llm_call_counter == 2

    def test_increment_llm_call_tools_counter(self) -> None:
        """_increment_llm_call_tools_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_call_tools_counter()

        assert optimizer.llm_call_tools_counter == 1

    def test_reset_counters(self) -> None:
        """_reset_counters should reset both counters to zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0


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


def test_pre_trial_invoked_during_evaluate(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.pre_trial_called = False

        def pre_trial(
            self, context: OptimizationContext, candidate: Any, round_handle=None
        ):
            self.pre_trial_called = True
            return candidate

    optimizer = TrialSpyOptimizer()
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.pre_trial_called is True


def test_on_trial_called_after_evaluation(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.on_trial_called = False

        def on_trial(
            self,
            context: OptimizationContext,
            prompts: dict[str, chat_prompt.ChatPrompt],
            score: float,
            prev_best_score: float | None = None,
        ) -> None:
            _ = context, prompts, score, prev_best_score
            self.on_trial_called = True

    optimizer = TrialSpyOptimizer()
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.on_trial_called is True


def test_post_trial_not_called_by_evaluate(
    monkeypatch: pytest.MonkeyPatch, simple_chat_prompt
) -> None:
    class TrialSpyOptimizer(ConcreteOptimizer):
        def __init__(self) -> None:
            super().__init__(model="gpt-4")
            self.post_trial_called = False

        def post_trial(
            self,
            context: OptimizationContext,
            candidate_handle: Any,
            *,
            score: float | None,
            **kwargs: Any,
        ) -> None:
            _ = context, candidate_handle, score, kwargs
            self.post_trial_called = True

    optimizer = TrialSpyOptimizer()
    dataset = make_mock_dataset()
    metric = MagicMock()
    agent = MagicMock()
    context = make_optimization_context(
        simple_chat_prompt,
        dataset=dataset,
        metric=metric,
        agent=agent,
        max_trials=5,
    )

    monkeypatch.setattr(
        optimizer,
        "evaluate_prompt",
        lambda **kwargs: 0.5,
    )

    optimizer.evaluate(context, {"main": simple_chat_prompt})
    assert optimizer.post_trial_called is False


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


class TestCleanup:
    """Tests for cleanup method."""

    def test_cleanup_resets_counters(self) -> None:
        """cleanup should reset call counters."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_llm_call_tools_counter()

        optimizer.cleanup()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0

    def test_cleanup_clears_opik_client(self) -> None:
        """cleanup should clear the Opik client reference."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._opik_client = cast(Any, MagicMock())

        optimizer.cleanup()

        assert optimizer._opik_client is None


class TestOptimizerInitialization:
    """Tests for optimizer initialization."""

    @pytest.mark.parametrize(
        "kwargs,expected,env_cleanup",
        [
            (
                {"model": "gpt-4"},
                {
                    "model": "gpt-4",
                    "verbose": 1,
                    "seed": 42,
                    "model_parameters": {},
                    "name": None,
                    "project_name": "Optimization",
                },
                True,
            ),
            (
                {
                    "model": "claude-3",
                    "verbose": 0,
                    "seed": 123,
                    "model_parameters": {"temperature": 0.7},
                    "name": "my-optimizer",
                },
                {
                    "model": "claude-3",
                    "verbose": 0,
                    "seed": 123,
                    "model_parameters": {"temperature": 0.7},
                    "name": "my-optimizer",
                },
                False,
            ),
        ],
    )
    def test_initialization(
        self,
        monkeypatch: pytest.MonkeyPatch,
        kwargs: dict[str, Any],
        expected: dict[str, Any],
        env_cleanup: bool,
    ) -> None:
        """Should set default and custom values correctly."""
        if env_cleanup:
            monkeypatch.delenv("OPIK_PROJECT_NAME", raising=False)
        optimizer = ConcreteOptimizer(**kwargs)
        for key, value in expected.items():
            assert getattr(optimizer, key) == value

    def test_reasoning_model_set_to_model(self) -> None:
        """reasoning_model should be set to the same value as model."""
        optimizer = ConcreteOptimizer(model="gpt-4o")

        assert optimizer.reasoning_model == "gpt-4o"


class TestDescribeAnnotation:
    """Tests for utils.tool_helpers.describe_annotation."""

    def test_returns_none_for_empty_annotation(self) -> None:
        """Should return None for inspect._empty."""
        import inspect

        result = tool_utils.describe_annotation(inspect._empty)

        assert result is None

    def test_returns_name_for_type(self) -> None:
        """Should return __name__ for type objects."""
        result = tool_utils.describe_annotation(str)

        assert result == "str"

    def test_returns_string_for_other(self) -> None:
        """Should return string representation for other objects."""
        result = tool_utils.describe_annotation("custom_annotation")

        assert result == "custom_annotation"


class TestNormalizePromptInput:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_single_prompt_returns_dict_and_true(
        self, optimizer, simple_chat_prompt
    ) -> None:
        prompts, is_single = optimizer._normalize_prompt_input(simple_chat_prompt)

        assert isinstance(prompts, dict)
        assert len(prompts) == 1
        assert simple_chat_prompt.name in prompts
        assert is_single is True

    def test_dict_prompt_returns_same_dict_and_false(
        self, optimizer, simple_chat_prompt
    ) -> None:
        input_dict = {"main": simple_chat_prompt}

        prompts, is_single = optimizer._normalize_prompt_input(input_dict)

        assert prompts is input_dict
        assert is_single is False


class TestCreateOptimizationRun:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_creates_optimization_and_sets_id(
        self, optimizer, mock_opik_client
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.return_value = MagicMock(id="opt-123")

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item, llm_output):
            _ = dataset_item, llm_output
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is not None
        assert optimizer.current_optimization_id == "opt-123"

    def test_returns_none_on_error(self, optimizer, mock_opik_client) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.side_effect = Exception("API error")

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item, llm_output):
            _ = dataset_item, llm_output
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is None
        assert optimizer.current_optimization_id is None


class TestSelectEvaluationDataset:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_returns_training_dataset_when_no_validation(self, optimizer) -> None:
        training_ds = make_mock_dataset(name="training")

        result = optimizer._select_evaluation_dataset(training_ds, None)

        assert result is training_ds

    def test_returns_validation_dataset_when_provided(self, optimizer) -> None:
        training_ds = make_mock_dataset(name="training")
        validation_ds = make_mock_dataset(name="validation")

        result = optimizer._select_evaluation_dataset(training_ds, validation_ds)

        assert result is validation_ds

    def test_returns_training_when_warn_unsupported_set(self, optimizer) -> None:
        """When warn_unsupported=True, validation_dataset is ignored and training is returned."""
        training_ds = make_mock_dataset(name="training")
        validation_ds = make_mock_dataset(name="validation")

        result = optimizer._select_evaluation_dataset(
            training_ds, validation_ds, warn_unsupported=True
        )

        # When warn_unsupported=True, the warning says validation is ignored,
        # so training dataset should be returned
        assert result is training_ds
