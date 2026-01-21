"""Unit tests for BaseOptimizer misc framework helpers (init + utility methods)."""

# mypy: disable-error-code=no-untyped-def

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock

import pytest

from opik_optimizer.utils import tool_helpers as tool_utils
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer
from tests.unit.test_helpers import make_mock_dataset


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
        self, optimizer: ConcreteOptimizer, simple_chat_prompt
    ) -> None:
        prompts, is_single = optimizer._normalize_prompt_input(simple_chat_prompt)

        assert isinstance(prompts, dict)
        assert len(prompts) == 1
        assert simple_chat_prompt.name in prompts
        assert is_single is True

    def test_dict_prompt_returns_same_dict_and_false(
        self, optimizer: ConcreteOptimizer, simple_chat_prompt
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
        self, optimizer: ConcreteOptimizer, mock_opik_client
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.return_value = MagicMock(id="opt-123")

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item: Any, llm_output: Any) -> float:
            _ = dataset_item, llm_output
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is not None
        assert optimizer.current_optimization_id == "opt-123"

    def test_returns_none_on_error(
        self, optimizer: ConcreteOptimizer, mock_opik_client
    ) -> None:
        mock_client = mock_opik_client()
        mock_client.create_optimization.side_effect = Exception("API error")

        mock_ds = make_mock_dataset(name="test-dataset")

        def metric(dataset_item: Any, llm_output: Any) -> float:
            _ = dataset_item, llm_output
            return 1.0

        result = optimizer._create_optimization_run(mock_ds, metric)

        assert result is None
        assert optimizer.current_optimization_id is None


class TestSelectEvaluationDataset:
    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_returns_training_dataset_when_no_validation(
        self, optimizer: ConcreteOptimizer
    ) -> None:
        training_ds = make_mock_dataset(name="training")

        result = optimizer._select_evaluation_dataset(training_ds, None)

        assert result is training_ds

    def test_returns_validation_dataset_when_provided(
        self, optimizer: ConcreteOptimizer
    ) -> None:
        training_ds = make_mock_dataset(name="training")
        validation_ds = make_mock_dataset(name="validation")

        result = optimizer._select_evaluation_dataset(training_ds, validation_ds)

        assert result is validation_ds

    def test_returns_training_when_warn_unsupported_set(
        self, optimizer: ConcreteOptimizer
    ) -> None:
        """When warn_unsupported=True, validation_dataset is ignored and training is returned."""
        training_ds = make_mock_dataset(name="training")
        validation_ds = make_mock_dataset(name="validation")

        result = optimizer._select_evaluation_dataset(
            training_ds, validation_ds, warn_unsupported=True
        )

        # When warn_unsupported=True, the warning says validation is ignored,
        # so training dataset should be returned
        assert result is training_ds
