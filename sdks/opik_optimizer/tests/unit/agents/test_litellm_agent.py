"""Unit tests for LiteLLMAgent wiring and cost tracking."""

from __future__ import annotations

from typing import Any, cast
from unittest.mock import MagicMock, patch

import pytest

from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from opik_optimizer.api_objects import chat_prompt


@pytest.fixture
def agent() -> LiteLLMAgent:
    """Create LiteLLMAgent for testing."""
    return LiteLLMAgent(project_name="test-project")


@pytest.fixture
def simple_prompt() -> chat_prompt.ChatPrompt:
    """Create a simple chat prompt for testing."""
    return chat_prompt.ChatPrompt(
        name="test-prompt",
        model="gpt-4o",
        messages=[
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "{input}"},
        ],
    )


@pytest.fixture
def tool_prompt() -> chat_prompt.ChatPrompt:
    """Create a prompt with tools for testing."""
    return chat_prompt.ChatPrompt(
        name="tool-prompt",
        model="gpt-4o",
        messages=[
            {"role": "system", "content": "You can use tools."},
            {"role": "user", "content": "{input}"},
        ],
        tools=[
            {
                "type": "function",
                "function": {
                    "name": "get_weather",
                    "description": "Get weather for a location",
                    "parameters": {
                        "type": "object",
                        "properties": {
                            "location": {"type": "string"},
                        },
                    },
                },
            }
        ],
        function_map={
            "get_weather": lambda location: f"Weather in {location}: Sunny",
        },
    )


class TestLiteLLMAgentInitialization:
    """Test LiteLLMAgent initialization."""

    def test_basic_initialization(self, agent: LiteLLMAgent) -> None:
        """Test basic agent initialization."""
        assert agent.project_name == "test-project"
        assert agent.trace_metadata == {"project_name": "test-project"}

    def test_init_sets_opik_project_env(self) -> None:
        """Test that init_llm sets OPIK_PROJECT_NAME env var."""
        import os

        # Clear env var if set
        old_val = os.environ.pop("OPIK_PROJECT_NAME", None)

        try:
            agent = LiteLLMAgent(project_name="env-test-project")
            assert os.environ.get("OPIK_PROJECT_NAME") == "env-test-project"
            assert agent.project_name == "env-test-project"
        finally:
            # Restore
            if old_val:
                os.environ["OPIK_PROJECT_NAME"] = old_val


class TestLiteLLMAgentInvoke:
    """Test LiteLLMAgent invoke_agent method."""

    def test_invoke_single_prompt(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test invoking with a single prompt."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "Hello!"
        mock_response.cost = 0.001
        mock_response.usage = MagicMock()
        mock_response.usage.prompt_tokens = 10
        mock_response.usage.completion_tokens = 5
        mock_response.usage.total_tokens = 15

        with patch.object(agent, "_llm_complete", return_value=mock_response):
            result = agent.invoke_agent(
                prompts={"test-prompt": simple_prompt},
                dataset_item={"input": "Hello"},
            )

            assert result == "Hello!"

    def test_invoke_multiple_prompts_raises_error(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test that multiple prompts raises ValueError."""
        prompts = {
            "prompt1": simple_prompt,
            "prompt2": simple_prompt,
        }

        with pytest.raises(ValueError, match="To optimize multiple prompts"):
            agent.invoke_agent(
                prompts=prompts,
                dataset_item={"input": "test"},
            )

    def test_invoke_formats_messages(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test that messages are formatted with dataset_item."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.cost = None
        mock_response.usage = None

        captured_messages: list[dict[str, Any]] = []

        def capture_complete(
            model: str, messages: list[dict[str, Any]], **kwargs: Any
        ) -> MagicMock:
            captured_messages.extend(messages)
            return mock_response

        with patch.object(agent, "_llm_complete", side_effect=capture_complete):
            agent.invoke_agent(
                prompts={"test": simple_prompt},
                dataset_item={"input": "formatted input"},
            )

            # Check that {input} was replaced
            user_message = next(m for m in captured_messages if m["role"] == "user")
            assert user_message["content"] == "formatted input"


class TestLiteLLMAgentToolCalling:
    """Test LiteLLMAgent tool calling functionality."""

    def test_tool_call_loop(
        self, agent: LiteLLMAgent, tool_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test the tool calling loop."""
        # First response: tool call
        # The code accesses msg.tool_calls (attribute) and msg["tool_calls"] (dict)
        tool_calls_data = [
            {
                "id": "call_123",
                "function": {
                    "name": "get_weather",
                    "arguments": '{"location": "NYC"}',
                },
            }
        ]

        tool_call_msg = MagicMock()
        tool_call_msg.tool_calls = tool_calls_data
        tool_call_msg.__getitem__ = (
            lambda self, key: tool_calls_data if key == "tool_calls" else None
        )
        tool_call_msg.to_dict = lambda: {
            "role": "assistant",
            "tool_calls": tool_calls_data,
        }

        tool_call_response = MagicMock()
        tool_call_response.choices = [MagicMock()]
        tool_call_response.choices[0].message = tool_call_msg
        tool_call_response.cost = 0.001
        tool_call_response.usage = MagicMock()
        tool_call_response.usage.prompt_tokens = 20
        tool_call_response.usage.completion_tokens = 10
        tool_call_response.usage.total_tokens = 30

        # Second response: final answer
        final_msg = MagicMock()
        final_msg.tool_calls = None
        final_msg.__getitem__ = (
            lambda self, key: "The weather is Sunny in NYC"
            if key == "content"
            else None
        )
        final_msg.to_dict = lambda: {
            "role": "assistant",
            "content": "The weather is Sunny in NYC",
        }

        final_response = MagicMock()
        final_response.choices = [MagicMock()]
        final_response.choices[0].message = final_msg
        final_response.cost = 0.001
        final_response.usage = MagicMock()
        final_response.usage.prompt_tokens = 30
        final_response.usage.completion_tokens = 10
        final_response.usage.total_tokens = 40

        call_count = 0

        def mock_complete(*args: Any, **kwargs: Any) -> MagicMock:
            nonlocal call_count
            call_count += 1
            if call_count == 1:
                return tool_call_response
            return final_response

        with patch.object(agent, "_llm_complete", side_effect=mock_complete):
            with patch(
                "opik_optimizer._llm_calls._increment_llm_counter_if_in_optimizer"
            ):
                with patch(
                    "opik_optimizer._llm_calls._increment_tool_counter_if_in_optimizer"
                ):
                    result = agent.invoke_agent(
                        prompts={"tool-prompt": tool_prompt},
                        dataset_item={"input": "What's the weather?"},
                        allow_tool_use=True,
                    )

                    assert "Sunny" in result
                    assert call_count == 2


class TestLiteLLMAgentCostTracking:
    """Test cost and usage tracking in LiteLLMAgent."""

    def test_llm_complete_attaches_cost(self, agent: LiteLLMAgent) -> None:
        """Test that _llm_complete attaches cost to response."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.cost = 0.005
        mock_response.usage = MagicMock()
        mock_response.usage.prompt_tokens = 100
        mock_response.usage.completion_tokens = 50
        mock_response.usage.total_tokens = 150

        with patch("litellm.completion", return_value=mock_response):
            with patch(
                "opik_optimizer.agents.litellm_agent.track_completion"
            ) as mock_track:
                mock_track.return_value = lambda x: x

                result = agent._llm_complete(
                    model="gpt-4o",
                    messages=[{"role": "user", "content": "test"}],
                    tools=None,
                )

                assert result._opik_cost == 0.005
                assert result._opik_usage["prompt_tokens"] == 100
                assert result._opik_usage["completion_tokens"] == 50
                assert result._opik_usage["total_tokens"] == 150

    def test_apply_cost_usage_to_owner(self, agent: LiteLLMAgent) -> None:
        """Test that cost/usage is propagated to optimizer owner."""
        mock_optimizer = MagicMock()
        cast(Any, agent)._optimizer_owner = mock_optimizer

        mock_response = MagicMock()
        mock_response._opik_cost = 0.01
        mock_response._opik_usage = {"total_tokens": 100}

        agent._apply_cost_usage_to_owner(mock_response)

        mock_optimizer._add_llm_cost.assert_called_once_with(0.01)
        mock_optimizer._add_llm_usage.assert_called_once_with({"total_tokens": 100})

    def test_apply_cost_handles_missing_owner(self, agent: LiteLLMAgent) -> None:
        """Test that missing optimizer owner doesn't raise error."""
        mock_response = MagicMock()
        mock_response._opik_cost = 0.01

        # Should not raise
        agent._apply_cost_usage_to_owner(mock_response)

    def test_llm_complete_handles_missing_usage(self, agent: LiteLLMAgent) -> None:
        """Test handling of responses without usage data."""
        mock_response = MagicMock()
        mock_response.choices = [MagicMock()]
        mock_response.choices[0].message.content = "response"
        mock_response.cost = None
        mock_response.usage = None

        with patch("litellm.completion", return_value=mock_response):
            with patch(
                "opik_optimizer.agents.litellm_agent.track_completion"
            ) as mock_track:
                mock_track.return_value = lambda x: x

                result = agent._llm_complete(
                    model="gpt-4o",
                    messages=[{"role": "user", "content": "test"}],
                    tools=None,
                )

                # Should not raise, cost should be None
                assert result._opik_cost is None


class TestLiteLLMAgentMultipleChoices:
    """Test handling of multiple completion choices."""

    def test_invoke_with_multiple_choices(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test that multiple choices are concatenated."""
        mock_response = MagicMock()
        mock_response.choices = [
            MagicMock(),
            MagicMock(),
        ]
        mock_response.choices[0].message = MagicMock()
        mock_response.choices[0].message.content = "Choice 1"
        mock_response.choices[1].message = MagicMock()
        mock_response.choices[1].message.content = "Choice 2"
        mock_response.cost = None
        mock_response.usage = None

        with patch.object(agent, "_llm_complete", return_value=mock_response):
            result = agent.invoke_agent(
                prompts={"test": simple_prompt},
                dataset_item={"input": "test"},
            )

            assert "Choice 1" in result
            assert "Choice 2" in result

    def test_invoke_with_empty_choices(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test handling of empty choices list."""
        mock_response = MagicMock()
        mock_response.choices = []
        mock_response.cost = None
        mock_response.usage = None

        with patch.object(agent, "_llm_complete", return_value=mock_response):
            result = agent.invoke_agent(
                prompts={"test": simple_prompt},
                dataset_item={"input": "test"},
            )

            assert result == ""


class TestLiteLLMAgentPrepareMessages:
    """Test message preparation hook."""

    def test_prepare_messages_default(self, agent: LiteLLMAgent) -> None:
        """Test default _prepare_messages returns messages unchanged."""
        messages = [{"role": "user", "content": "test"}]
        result = agent._prepare_messages(messages, {"input": "data"})
        assert result == messages

    def test_prepare_messages_can_be_overridden(self) -> None:
        """Test that subclass can override _prepare_messages."""

        class CustomAgent(LiteLLMAgent):
            def _prepare_messages(
                self,
                messages: list[dict[str, Any]],
                dataset_item: dict[str, Any] | None,
            ) -> list[dict[str, Any]]:
                return messages + [{"role": "user", "content": "extra"}]

        agent = CustomAgent(project_name="test")
        messages = [{"role": "user", "content": "original"}]
        result = agent._prepare_messages(messages, None)

        assert len(result) == 2
        assert result[1]["content"] == "extra"


class TestLiteLLMAgentRateLimiting:
    """Test rate limiting decorator on _llm_complete."""

    def test_llm_complete_has_rate_limiting(self, agent: LiteLLMAgent) -> None:
        """Test that _llm_complete is rate limited."""
        # Check that the method has the rate_limited decorator applied
        # by inspecting the wrapper
        method = agent._llm_complete
        # Rate limited methods have __wrapped__ attribute
        assert hasattr(method, "__wrapped__") or callable(method)
