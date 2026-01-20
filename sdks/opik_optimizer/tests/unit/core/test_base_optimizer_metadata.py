"""Unit tests for BaseOptimizer metadata/config helpers."""

from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.core import agent as agent_utils
from opik_optimizer.core import state as state_utils
from tests.unit.fixtures.base_optimizer_test_helpers import ConcreteOptimizer


class TestBuildAgentConfig:
    """Tests for core.agent.build_agent_config."""

    @pytest.fixture
    def optimizer(self) -> ConcreteOptimizer:
        return ConcreteOptimizer(model="gpt-4")

    def test_includes_prompt_dict(self, optimizer, simple_chat_prompt) -> None:
        """Should include prompt content from to_dict()."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=simple_chat_prompt
        )

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

        assert has_content, (
            "Config should include prompt content (system, user, or messages)"
        )

    def test_includes_model(self, optimizer, simple_chat_prompt) -> None:
        """Should include model name."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=simple_chat_prompt
        )

        assert "model" in config

    def test_includes_optimizer_name(self, optimizer, simple_chat_prompt) -> None:
        """Should include optimizer class name."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=simple_chat_prompt
        )

        assert config["optimizer"] == "ConcreteOptimizer"

    def test_includes_tools(self, optimizer, chat_prompt_with_tools) -> None:
        """Should include serialized tools."""
        config = agent_utils.build_agent_config(
            optimizer=optimizer, prompt=chat_prompt_with_tools
        )

        assert "tools" in config
        assert len(config["tools"]) == 2


class TestGetOptimizerMetadata:
    """Tests for get_optimizer_metadata and build_optimizer_metadata."""

    def test_subclass_metadata_is_included(self) -> None:
        """Subclass metadata should be included via get_optimizer_metadata."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = state_utils.build_optimizer_metadata(optimizer)

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

        metadata = state_utils.build_optimizer_metadata(optimizer)

        assert metadata["name"] == "ConcreteOptimizer"
        assert metadata["model"] == "gpt-4"
        assert metadata["seed"] == 123
        assert metadata["model_parameters"] == {"temperature": 0.5}

    def test_includes_version(self) -> None:
        """Should include optimizer version."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = state_utils.build_optimizer_metadata(optimizer)

        assert "version" in metadata


class TestBuildOptimizationMetadata:
    """Tests for build_optimization_metadata."""

    def test_includes_optimizer_name(self) -> None:
        """Should include optimizer class name."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        metadata = state_utils.build_optimization_metadata(optimizer)

        assert metadata["optimizer"] == "ConcreteOptimizer"

    def test_includes_custom_name_when_set(self) -> None:
        """Should include custom name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4", name="my-optimization")

        metadata = state_utils.build_optimization_metadata(optimizer)

        assert metadata["name"] == "my-optimization"

    def test_includes_agent_class_when_provided(self) -> None:
        """Should include agent class name when provided."""
        optimizer = ConcreteOptimizer(model="gpt-4")

        from opik_optimizer.agents import OptimizableAgent

        class CustomAgent(OptimizableAgent):
            def invoke_agent(
                self,
                prompts: dict[str, chat_prompt.ChatPrompt],
                dataset_item: dict[str, Any],
                allow_tool_use: bool = False,
                seed: int | None = None,
            ) -> str:
                return "output"

        metadata = state_utils.build_optimization_metadata(
            optimizer, agent_class=CustomAgent
        )

        assert metadata["agent_class"] == "CustomAgent"

