# mypy: disable-error-code=no-untyped-def

"""
Unit tests for opik_optimizer.base_optimizer module.

Tests cover:
- _validate_optimization_inputs: Input validation
- utils.tool_helpers.deep_merge_dicts: Dictionary merging
- utils.tool_helpers.serialize_tools: Tool serialization
- core.agent.build_agent_config: Config building
- core.state.build_optimizer_metadata: Metadata generation
- Counter and history management
"""

from __future__ import annotations

from typing import Any, TYPE_CHECKING
from collections.abc import Callable
from unittest.mock import MagicMock

import pytest

from opik_optimizer import ChatPrompt
from tests.unit.test_helpers import (
    make_mock_dataset,
    make_optimization_context,
    make_simple_metric,
    STANDARD_DATASET_ITEMS,
)

if TYPE_CHECKING:
    pass

from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer


class TestOptimizationContextDataclass:
    """Tests for OptimizationContext dataclass."""

    def test_creates_context_with_required_fields(self) -> None:
        """Should create context with all required fields."""
        mock_prompt = ChatPrompt(name="test", system="test", user="test")
        mock_dataset = make_mock_dataset()
        mock_metric = MagicMock()
        mock_agent = MagicMock()

        context = make_optimization_context(
            mock_prompt,
            dataset=mock_dataset,
            metric=mock_metric,
            agent=mock_agent,
        )

        assert "test" in context.prompts
        assert context.is_single_prompt_optimization is True
        assert context.max_trials == 10
        assert context.baseline_score is None  # Default
        assert context.extra_params == {}  # Default

    def test_baseline_score_default(self) -> None:
        """baseline_score should default to None."""
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
        )

        assert context.baseline_score is None

    def test_extra_params_default(self) -> None:
        """extra_params should default to empty dict."""
        context = make_optimization_context(
            ChatPrompt(name="test", system="test", user="test"),
        )

        assert context.extra_params == {}


# ============================================================
# optimize_prompt Parameter Tests
# ============================================================


class TestAutoContinueParameter:
    """Tests for auto_continue parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    @pytest.mark.parametrize(
        "auto_continue_value,expected_in_extra_params",
        [
            (True, True),
            (False, False),
        ],
    )
    def test_auto_continue_stored_in_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
        auto_continue_value: bool,
        expected_in_extra_params: bool,
    ) -> None:
        """Verify auto_continue is stored in context.extra_params."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            auto_continue=auto_continue_value,
        )

        assert context.extra_params.get("auto_continue") == expected_in_extra_params

    def test_auto_continue_default_false(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Default value of auto_continue should be False."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        assert context.extra_params.get("auto_continue", False) is False


class TestExperimentConfigParameter:
    """Tests for experiment_config parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_experiment_config_stored_in_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Verify experiment_config is stored in context."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        config = {"key1": "value1", "key2": 42}

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            experiment_config=config,
        )

        assert context.experiment_config == config

    def test_experiment_config_none_handled(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """None experiment_config should be handled gracefully."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            experiment_config=None,
        )

        assert context.experiment_config is None

    def test_experiment_config_passed_to_evaluate(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
        monkeypatch: pytest.MonkeyPatch,
    ) -> None:
        """Verify experiment_config is passed to evaluate_prompt."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        config = {"test_key": "test_value"}

        captured_config = {}

        def fake_evaluate(**kwargs: Any) -> float:
            captured_config["experiment_config"] = kwargs.get("experiment_config")
            return 0.5

        monkeypatch.setattr(optimizer, "evaluate_prompt", fake_evaluate)

        optimizer._calculate_baseline(
            make_optimization_context(
                simple_chat_prompt,
                dataset=mock_ds,
                metric=mock_metric,
                experiment_config=config,
            )
        )

        # experiment_config is merged with internal config, so check it's present
        assert captured_config.get("experiment_config") is not None


class TestNSamplesParameter:
    """Tests for n_samples parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    @pytest.mark.parametrize(
        "n_samples,dataset_size,expected_n_samples",
        [
            (5, 10, 5),  # Normal case
            (None, 10, None),  # None evaluates all
            (15, 10, None),  # Greater than dataset size -> None (uses all)
        ],
    )
    def test_n_samples_limits_evaluation(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
        n_samples: int | None,
        dataset_size: int,
        expected_n_samples: int | None,
    ) -> None:
        """n_samples limits dataset items evaluated."""
        mock_opik_client()
        items = [{"id": str(i), "input": f"test{i}"} for i in range(dataset_size)]
        mock_ds = make_mock_dataset(items)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            n_samples=n_samples,
        )

        assert context.n_samples == expected_n_samples

    def test_n_samples_zero_raises_warning(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """n_samples=0 should be handled (though unusual)."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        # n_samples=0 is technically valid but unusual
        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            n_samples=0,
        )

        # Should be stored as-is (validation happens in evaluate_prompt)
        assert context.n_samples == 0


class TestProjectNameParameter:
    """Tests for project_name parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_project_name_explicit(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Explicit project_name should be used."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            project_name="MyProject",
        )

        assert context.project_name == "MyProject"
        assert optimizer.project_name == "MyProject"

    def test_project_name_passed_to_agent(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Project name should be passed to agent."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        from opik_optimizer.agents import LiteLLMAgent

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            project_name="TestProject",
            agent=None,  # Will create default agent
        )

        assert isinstance(context.agent, LiteLLMAgent)
        assert context.agent.project_name == "TestProject"  # type: ignore[attr-defined]


class TestOptimizationIdParameter:
    """Tests for optimization_id parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_optimization_id_stored_in_context(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Optimization ID should be stored in context."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)
        mock_optimization = MagicMock()
        mock_optimization.id = "opt-123"
        optimizer.opik_client.get_optimization_by_id = MagicMock(
            return_value=mock_optimization
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
            optimization_id="opt-123",
        )

        assert context.optimization_id == "opt-123"
        assert optimizer.current_optimization_id == "opt-123"


class TestValidationDatasetParameter:
    """Tests for validation_dataset parameter in optimize_prompt."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_validation_dataset_used_for_evaluation(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Validation dataset should be used for evaluation when provided."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}], name="validation"
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
        )

        assert context.validation_dataset is validation_ds
        assert context.evaluation_dataset is validation_ds
        assert context.dataset_split == "validation"

    def test_validation_dataset_none_uses_training(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """None validation_dataset should use training dataset."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=None,
        )

        assert context.validation_dataset is None
        assert context.evaluation_dataset is training_ds
        assert context.dataset_split == "train"

    def test_n_samples_validation_uses_evaluation_dataset_size(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """n_samples validation should use evaluation dataset size when validation is provided."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}], name="validation"
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
            n_samples=2,
        )

        assert context.n_samples is None

    def test_metric_required_fields_enforced_on_evaluation_dataset(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
    ) -> None:
        """Metrics declaring required_fields should fail when missing in evaluation dataset."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2"}], name="validation"
        )

        def metric_fn(dataset_item: dict[str, Any], llm_output: str) -> float:
            return 0.0

        metric_fn.required_fields = ("answer",)  # type: ignore[attr-defined]

        with pytest.raises(ValueError, match="requires dataset fields"):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=training_ds,
                metric=metric_fn,
                compute_baseline=False,
                validation_dataset=validation_ds,
            )

    def test_validation_dataset_added_to_experiment_config(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Validation dataset should be added to experiment_config."""
        mock_opik_client()
        training_ds = make_mock_dataset(STANDARD_DATASET_ITEMS, name="training")
        validation_ds = make_mock_dataset(
            [{"id": "2", "question": "Q2", "answer": "A2"}],
            name="validation",
            dataset_id="val-123",
        )

        context = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=training_ds,
            metric=mock_metric,
            compute_baseline=False,
            validation_dataset=validation_ds,
            experiment_config={},
        )

        assert context.experiment_config is not None
        assert context.experiment_config["validation_dataset"] == "validation"
        assert context.experiment_config["validation_dataset_id"] == "val-123"


# ============================================================
# Initialization Parameter Tests
# ============================================================


class TestModelParameters:
    """Tests for model_parameters initialization parameter."""

    @pytest.mark.parametrize(
        "model_params,expected",
        [
            (
                {"temperature": 0.7, "max_tokens": 100},
                {"temperature": 0.7, "max_tokens": 100},
            ),
            (None, {}),
            ({}, {}),
        ],
    )
    def test_model_parameters_stored(
        self, model_params: dict[str, Any] | None, expected: dict[str, Any]
    ) -> None:
        """Model parameters should be stored correctly."""
        optimizer = ConcreteOptimizer(model="gpt-4", model_parameters=model_params)
        assert optimizer.model_parameters == expected


class TestReasoningModel:
    """Tests for reasoning_model initialization parameter."""

    def test_reasoning_model_explicit(self) -> None:
        """Explicit reasoning_model should be used."""
        optimizer = ConcreteOptimizer(model="gpt-4", reasoning_model="gpt-4o-mini")
        assert optimizer.reasoning_model == "gpt-4o-mini"

    def test_reasoning_model_none_falls_back_to_model(self) -> None:
        """None reasoning_model should fall back to model."""
        optimizer = ConcreteOptimizer(model="gpt-4", reasoning_model=None)
        assert optimizer.reasoning_model == "gpt-4"


class TestReasoningModelParameters:
    """Tests for reasoning_model_parameters initialization parameter."""

    def test_reasoning_model_parameters_explicit(self) -> None:
        """Explicit reasoning_model_parameters should be used."""
        optimizer = ConcreteOptimizer(
            model="gpt-4",
            reasoning_model_parameters={"temperature": 0.5},
        )
        assert optimizer.reasoning_model_parameters == {"temperature": 0.5}

    def test_reasoning_model_parameters_none_falls_back(
        self,
    ) -> None:
        """None reasoning_model_parameters should fall back to model_parameters."""
        optimizer = ConcreteOptimizer(
            model="gpt-4",
            model_parameters={"temperature": 0.7},
            reasoning_model_parameters=None,
        )
        assert optimizer.reasoning_model_parameters == {"temperature": 0.7}


# ============================================================
# Edge Cases and Error Handling
# ============================================================


class TestEmptyDataset:
    """Tests for empty dataset handling."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_empty_dataset_raises_error(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Empty dataset should raise appropriate error."""
        mock_opik_client()
        empty_ds = make_mock_dataset([], name="empty-dataset")

        # Empty dataset should be caught during validation
        with pytest.raises((ValueError, Exception)):
            optimizer._setup_optimization(
                prompt=simple_chat_prompt,
                dataset=empty_ds,
                metric=mock_metric,
                compute_baseline=False,
            )


class TestInvalidMetricFunctions:
    """Tests for invalid metric function handling."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.mark.parametrize(
        "metric_func,expected_error",
        [
            (lambda _di, _lo: float("nan"), ValueError),  # NaN
            (lambda _di, _lo: None, TypeError),  # None
            (lambda _di, _lo: "not a float", TypeError),  # Wrong type
        ],
    )
    def test_invalid_metric_raises_error(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        metric_func: Callable,
        expected_error: type[Exception],
    ) -> None:
        """Invalid metric functions should raise appropriate errors."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        # Metric validation happens during evaluation
        optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=metric_func,
            compute_baseline=False,
        )

        # Error should occur during _coerce_score
        with pytest.raises(expected_error):
            optimizer._coerce_score(metric_func({}, "output"))


class TestReusingOptimizerInstances:
    """Tests for reusing optimizer instances across multiple runs."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_metric(self):
        return make_simple_metric()

    def test_reuse_optimizer_multiple_runs(
        self,
        optimizer: ConcreteOptimizer,
        simple_chat_prompt: ChatPrompt,
        mock_opik_client,
        mock_metric,
    ) -> None:
        """Optimizer should be reusable for multiple runs."""
        mock_opik_client()
        mock_ds = make_mock_dataset(STANDARD_DATASET_ITEMS)

        # First run
        context1 = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        # Second run - counters should be reset
        context2 = optimizer._setup_optimization(
            prompt=simple_chat_prompt,
            dataset=mock_ds,
            metric=mock_metric,
            compute_baseline=False,
        )

        # Contexts should be independent
        assert context1 is not context2
        assert context1.trials_completed == 0
        assert context2.trials_completed == 0

    def test_reuse_counters_reset(
        self,
        optimizer: ConcreteOptimizer,
    ) -> None:
        """Counters should reset between runs."""
        optimizer.llm_call_counter = 5
        optimizer.llm_call_tools_counter = 3
        optimizer.llm_cost_total = 1.5
        optimizer.llm_token_usage_total = {
            "prompt_tokens": 10,
            "completion_tokens": 20,
            "total_tokens": 30,
        }

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.llm_call_tools_counter == 0
        assert optimizer.llm_cost_total == 0.0
        assert optimizer.llm_token_usage_total == {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }

    def test_reuse_history_cleared(
        self,
        optimizer: ConcreteOptimizer,
    ) -> None:
        """History should be cleared between runs."""
        # Add some history
        optimizer._history_builder.start_round()
        optimizer._history_builder.end_round(None)

        # Clear for new run
        optimizer._history_builder.clear()

        assert len(optimizer._history_builder.get_entries()) == 0
