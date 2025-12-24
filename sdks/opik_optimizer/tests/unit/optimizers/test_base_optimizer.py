"""
Unit tests for opik_optimizer.base_optimizer module.

Tests cover:
- _validate_optimization_inputs: Input validation
- _deep_merge_dicts: Dictionary merging
- _serialize_tools: Tool serialization
- _build_agent_config: Config building
- get_optimizer_metadata: Metadata generation
- Counter and history management
"""

import pytest
from typing import Any
from unittest.mock import MagicMock

from opik_optimizer import ChatPrompt
from opik_optimizer.base_optimizer import BaseOptimizer, OptimizationRound


class ConcreteOptimizer(BaseOptimizer):
    """Concrete implementation for testing the abstract BaseOptimizer."""

    def optimize_prompt(self, prompt, dataset, metric, **kwargs):
        """Required abstract method implementation."""
        return MagicMock()

    def get_optimizer_metadata(self) -> dict[str, Any]:
        """Return test-specific metadata."""
        return {"test_param": "test_value", "count": 42}


class TestValidateOptimizationInputs:
    """Tests for _validate_optimization_inputs method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    @pytest.fixture
    def mock_dataset(self, mock_dataset):
        """Use the mock_dataset fixture from conftest."""
        return mock_dataset([{"id": "1", "question": "Q1", "answer": "A1"}])

    @pytest.fixture
    def mock_metric(self):
        def metric(dataset_item, llm_output):
            return 1.0

        return metric

    def test_accepts_valid_single_prompt(
        self, optimizer, simple_chat_prompt, mock_dataset, mock_metric
    ) -> None:
        """Should accept a valid ChatPrompt, Dataset, and metric."""
        # Patch Dataset check
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        # Should not raise
        optimizer._validate_optimization_inputs(simple_chat_prompt, mock_ds, mock_metric)

    def test_accepts_valid_prompt_dict(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should accept a dict of ChatPrompt objects."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        prompt_dict = {"main": simple_chat_prompt}

        # Should not raise
        optimizer._validate_optimization_inputs(prompt_dict, mock_ds, mock_metric)

    def test_rejects_non_chatprompt(self, optimizer, mock_metric) -> None:
        """Should reject prompt that is not a ChatPrompt."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs("not a prompt", mock_ds, mock_metric)

    def test_rejects_dict_with_non_chatprompt_values(self, optimizer, mock_metric) -> None:
        """Should reject dict containing non-ChatPrompt values."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)
        invalid_dict = {"main": "not a prompt"}

        with pytest.raises(ValueError, match="ChatPrompt"):
            optimizer._validate_optimization_inputs(invalid_dict, mock_ds, mock_metric)

    def test_rejects_non_dataset(
        self, optimizer, simple_chat_prompt, mock_metric
    ) -> None:
        """Should reject non-Dataset object."""
        with pytest.raises(ValueError, match="Dataset"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt, "not a dataset", mock_metric
            )

    def test_rejects_non_callable_metric(self, optimizer, simple_chat_prompt) -> None:
        """Should reject metric that is not callable."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        with pytest.raises(ValueError, match="function"):
            optimizer._validate_optimization_inputs(
                simple_chat_prompt, mock_ds, "not a function"
            )

    def test_rejects_multimodal_when_not_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should reject multimodal prompts when support_content_parts=False."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        with pytest.raises(ValueError, match="content parts"):
            optimizer._validate_optimization_inputs(
                multimodal_chat_prompt,
                mock_ds,
                mock_metric,
                support_content_parts=False,
            )

    def test_accepts_multimodal_when_supported(
        self, optimizer, multimodal_chat_prompt, mock_metric
    ) -> None:
        """Should accept multimodal prompts when support_content_parts=True."""
        from opik import Dataset

        mock_ds = MagicMock(spec=Dataset)

        # Should not raise
        optimizer._validate_optimization_inputs(
            multimodal_chat_prompt,
            mock_ds,
            mock_metric,
            support_content_parts=True,
        )


class TestDeepMergeDicts:
    """Tests for _deep_merge_dicts static method."""

    def test_merges_flat_dicts(self) -> None:
        """Should merge two flat dictionaries."""
        base = {"a": 1, "b": 2}
        overrides = {"b": 3, "c": 4}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"a": 1, "b": 3, "c": 4}

    def test_deep_merges_nested_dicts(self) -> None:
        """Should recursively merge nested dictionaries."""
        base = {"level1": {"a": 1, "b": 2}, "other": "value"}
        overrides = {"level1": {"b": 3, "c": 4}}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"level1": {"a": 1, "b": 3, "c": 4}, "other": "value"}

    def test_override_replaces_non_dict_with_dict(self) -> None:
        """Should replace non-dict value with dict value."""
        base = {"key": "string_value"}
        overrides = {"key": {"nested": "value"}}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"key": {"nested": "value"}}

    def test_override_replaces_dict_with_non_dict(self) -> None:
        """Should replace dict value with non-dict value."""
        base = {"key": {"nested": "value"}}
        overrides = {"key": "string_value"}

        result = BaseOptimizer._deep_merge_dicts(base, overrides)

        assert result == {"key": "string_value"}

    def test_does_not_modify_original_dicts(self) -> None:
        """Should not modify the input dictionaries."""
        base = {"a": {"b": 1}}
        overrides = {"a": {"c": 2}}

        BaseOptimizer._deep_merge_dicts(base, overrides)

        assert base == {"a": {"b": 1}}
        assert overrides == {"a": {"c": 2}}

    def test_handles_empty_base(self) -> None:
        """Should handle empty base dictionary."""
        result = BaseOptimizer._deep_merge_dicts({}, {"a": 1})
        assert result == {"a": 1}

    def test_handles_empty_overrides(self) -> None:
        """Should handle empty overrides dictionary."""
        result = BaseOptimizer._deep_merge_dicts({"a": 1}, {})
        assert result == {"a": 1}


class TestSerializeTools:
    """Tests for _serialize_tools static method."""

    def test_serializes_tools_list(self, chat_prompt_with_tools) -> None:
        """Should return deep copy of tools list."""
        result = BaseOptimizer._serialize_tools(chat_prompt_with_tools)

        assert isinstance(result, list)
        assert len(result) == 2
        assert result[0]["function"]["name"] == "search"

    def test_returns_empty_list_when_no_tools(self, simple_chat_prompt) -> None:
        """Should return empty list when prompt has no tools."""
        result = BaseOptimizer._serialize_tools(simple_chat_prompt)

        assert result == []

    def test_returns_deep_copy(self, chat_prompt_with_tools) -> None:
        """Should return a deep copy, not reference original."""
        result = BaseOptimizer._serialize_tools(chat_prompt_with_tools)

        # Modify the result
        result[0]["function"]["name"] = "modified"

        # Original should be unchanged
        assert chat_prompt_with_tools.tools[0]["function"]["name"] == "search"


class TestBuildAgentConfig:
    """Tests for _build_agent_config method."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_includes_prompt_dict(self, optimizer, simple_chat_prompt) -> None:
        """Should include prompt content from to_dict()."""
        config = optimizer._build_agent_config(simple_chat_prompt)

        # Config should include prompt content - simple_chat_prompt has system and user
        # Verify at least one content key exists and has valid content
        has_content = False
        if "system" in config:
            assert isinstance(config["system"], str)
            assert len(config["system"]) > 0
            has_content = True
        if "user" in config:
            assert isinstance(config["user"], str)
            assert len(config["user"]) > 0
            has_content = True
        if "messages" in config:
            assert isinstance(config["messages"], list)
            assert len(config["messages"]) > 0
            has_content = True
        
        assert has_content, "Config should include prompt content (system, user, or messages)"

    def test_includes_model(self, optimizer, simple_chat_prompt) -> None:
        """Should include model name."""
        config = optimizer._build_agent_config(simple_chat_prompt)

        assert "model" in config

    def test_includes_optimizer_name(self, optimizer, simple_chat_prompt) -> None:
        """Should include optimizer class name."""
        config = optimizer._build_agent_config(simple_chat_prompt)

        assert config["optimizer"] == "ConcreteOptimizer"

    def test_includes_tools(self, optimizer, chat_prompt_with_tools) -> None:
        """Should include serialized tools."""
        config = optimizer._build_agent_config(chat_prompt_with_tools)

        assert "tools" in config
        assert len(config["tools"]) == 2


class TestGetOptimizerMetadata:
    """Tests for get_optimizer_metadata and _build_optimizer_metadata."""

    def test_subclass_metadata_is_included(self) -> None:
        """Subclass metadata should be included via get_optimizer_metadata."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = optimizer._build_optimizer_metadata()

        assert "parameters" in metadata
        assert metadata["parameters"]["test_param"] == "test_value"
        assert metadata["parameters"]["count"] == 42

    def test_includes_base_metadata(self) -> None:
        """Should include base optimizer metadata."""
        optimizer = ConcreteOptimizer(
            model="gpt-4",
            seed=123,
            model_parameters={"temperature": 0.5},
        )

        metadata = optimizer._build_optimizer_metadata()

        assert metadata["name"] == "ConcreteOptimizer"
        assert metadata["model"] == "gpt-4"
        assert metadata["seed"] == 123
        assert metadata["model_parameters"] == {"temperature": 0.5}

    def test_includes_version(self) -> None:
        """Should include optimizer version."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = optimizer._build_optimizer_metadata()

        assert "version" in metadata


class TestBuildOptimizationMetadata:
    """Tests for _build_optimization_metadata method."""

    def test_includes_optimizer_name(self) -> None:
        """Should include optimizer class name."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = optimizer._build_optimization_metadata()

        assert metadata["optimizer"] == "ConcreteOptimizer"

    def test_includes_custom_name_when_set(self) -> None:
        """Should include custom name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4", name="my-optimization")

        metadata = optimizer._build_optimization_metadata()

        assert metadata["name"] == "my-optimization"

    def test_includes_agent_class_when_provided(self) -> None:
        """Should include agent class name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        class CustomAgent:
            pass

        metadata = optimizer._build_optimization_metadata(agent_class=CustomAgent)

        assert metadata["agent_class"] == "CustomAgent"


class TestCounterManagement:
    """Tests for counter management methods."""

    def test_counters_start_at_zero(self) -> None:
        """Counters should start at zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_increment_llm_counter(self) -> None:
        """_increment_llm_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_llm_counter()
        optimizer._increment_llm_counter()

        assert optimizer.llm_call_counter == 2

    def test_increment_tool_counter(self) -> None:
        """_increment_tool_counter should increment the counter."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        optimizer._increment_tool_counter()

        assert optimizer.tool_call_counter == 1

    def test_reset_counters(self) -> None:
        """_reset_counters should reset both counters to zero."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        optimizer._reset_counters()

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0


class TestHistoryManagement:
    """Tests for history management methods."""

    def test_history_starts_empty(self) -> None:
        """History should start empty."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.get_history() == []

    def test_add_to_history(self, simple_chat_prompt) -> None:
        """_add_to_history should add round data."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        round_data = OptimizationRound(
            round_number=1,
            current_prompt=simple_chat_prompt,
            current_score=0.5,
            generated_prompts=[],
            best_prompt=simple_chat_prompt,
            best_score=0.5,
            improvement=0.0,
        )

        optimizer._add_to_history(round_data)

        history = optimizer.get_history()
        assert len(history) == 1
        assert history[0].round_number == 1

    def test_cleanup_clears_history(self, simple_chat_prompt) -> None:
        """cleanup should clear the history."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        round_data = OptimizationRound(
            round_number=1,
            current_prompt=simple_chat_prompt,
            current_score=0.5,
            generated_prompts=[],
            best_prompt=simple_chat_prompt,
            best_score=0.5,
            improvement=0.0,
        )
        optimizer._add_to_history(round_data)

        optimizer.cleanup()

        assert optimizer.get_history() == []


class TestCleanup:
    """Tests for cleanup method."""

    def test_cleanup_resets_counters(self) -> None:
        """cleanup should reset call counters."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._increment_llm_counter()
        optimizer._increment_tool_counter()

        optimizer.cleanup()

        assert optimizer.llm_call_counter == 0
        assert optimizer.tool_call_counter == 0

    def test_cleanup_clears_opik_client(self) -> None:
        """cleanup should clear the Opik client reference."""
        optimizer = ConcreteOptimizer(model="gpt-4")
        optimizer._opik_client = MagicMock()

        optimizer.cleanup()

        assert optimizer._opik_client is None


class TestOptimizerInitialization:
    """Tests for optimizer initialization."""

    def test_default_values(self) -> None:
        """Should set default values correctly."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        assert optimizer.model == "gpt-4"
        assert optimizer.verbose == 1
        assert optimizer.seed == 42
        assert optimizer.model_parameters == {}
        assert optimizer.name is None
        assert optimizer.project_name == "Optimization"

    def test_custom_values(self) -> None:
        """Should accept custom values."""
        optimizer = ConcreteOptimizer(
            model="claude-3",
            verbose=0,
            seed=123,
            model_parameters={"temperature": 0.7},
            name="my-optimizer",
        )

        assert optimizer.model == "claude-3"
        assert optimizer.verbose == 0
        assert optimizer.seed == 123
        assert optimizer.model_parameters == {"temperature": 0.7}
        assert optimizer.name == "my-optimizer"

    def test_reasoning_model_set_to_model(self) -> None:
        """reasoning_model should be set to the same value as model."""
        optimizer = ConcreteOptimizer(model="gpt-4o")

        assert optimizer.reasoning_model == "gpt-4o"


class TestDescribeAnnotation:
    """Tests for _describe_annotation static method."""

    def test_returns_none_for_empty_annotation(self) -> None:
        """Should return None for inspect._empty."""
        import inspect

        result = BaseOptimizer._describe_annotation(inspect._empty)

        assert result is None

    def test_returns_name_for_type(self) -> None:
        """Should return __name__ for type objects."""
        result = BaseOptimizer._describe_annotation(str)

        assert result == "str"

    def test_returns_string_for_other(self) -> None:
        """Should return string representation for other objects."""
        result = BaseOptimizer._describe_annotation("custom_annotation")

        assert result == "custom_annotation"

