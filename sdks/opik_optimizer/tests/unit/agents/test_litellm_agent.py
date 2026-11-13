"""Unit tests for LiteLLMAgent wiring and cost tracking."""

from __future__ import annotations

from typing import Any
from unittest.mock import MagicMock, patch

import pytest

from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from opik_optimizer.agents.litellm_agent import _sanitize_tool_arguments_for_logging
from opik_optimizer.api_objects import chat_prompt
from tests.unit.fixtures.builders import make_litellm_completion_response
from tests.unit.fixtures import system_message, user_message


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
            system_message("You are a helpful assistant."),
            user_message("{input}"),
        ],
    )


@pytest.fixture
def tool_prompt() -> chat_prompt.ChatPrompt:
    """Create a prompt with tools for testing."""
    return chat_prompt.ChatPrompt(
        name="tool-prompt",
        model="gpt-4o",
        messages=[
            system_message("You can use tools."),
            user_message("{input}"),
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


def test_sanitize_tool_arguments_for_logging_redacts_sensitive_keys() -> None:
    args = {
        "api_key": "super-secret-value",
        "nested": {"token": "abcd", "query": "hello"},
        "password_hint": "something",
        "normal": "x" * 80,
    }
    sanitized = _sanitize_tool_arguments_for_logging(args)

    assert sanitized["api_key"] == "***REDACTED***"
    assert sanitized["nested"]["token"] == "***REDACTED***"
    assert sanitized["password_hint"] == "***REDACTED***"
    assert sanitized["nested"]["query"] == "hello"
    assert sanitized["normal"].endswith("...")


class TestLiteLLMAgentInvoke:
    """Test LiteLLMAgent invoke_agent method."""

    def test_invoke_single_prompt(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test invoking with a single prompt."""
        mock_response = make_litellm_completion_response(
            "Hello!",
            cost=0.001,
            usage={"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15},
        )

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

    def test_tool_loop_returns_last_tool_response_when_capped(
        self, agent: LiteLLMAgent
    ) -> None:
        """Tool loop should return the last tool response when max iterations hit."""
        tool_called: list[dict[str, Any]] = []

        def tool_fn(**kwargs: Any) -> str:
            tool_called.append(kwargs)
            return "tool-response"

        prompt = chat_prompt.ChatPrompt(system="s", user="u")
        prompt.tools = [
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search",
                    "parameters": {"type": "object", "properties": {"q": {}}},
                },
            }
        ]
        prompt.function_map = {"search": tool_fn}

        class _ToolMessage:
            def __init__(self) -> None:
                self.tool_calls = [
                    {
                        "id": "call_1",
                        "function": {"name": "search", "arguments": '{"q": "x"}'},
                    }
                ]
                self.content = ""

            def to_dict(self) -> dict[str, Any]:
                return {"tool_calls": self.tool_calls, "content": self.content}

            def __getitem__(self, key: str) -> Any:
                return {"tool_calls": self.tool_calls, "content": self.content}[key]

        message = _ToolMessage()

        mock_response = make_litellm_completion_response(message=message)

        with (
            patch.object(
                agent,
                "_llm_complete",
                return_value=mock_response,
            ),
            patch(
                "opik_optimizer.agents.litellm_agent.tool_call_max_iterations",
                return_value=1,
            ),
        ):
            result = agent.invoke_agent(
                prompts={"p": prompt},
                dataset_item={"input": "x"},
                allow_tool_use=True,
            )

        assert tool_called
        assert result == "tool-response"

    def test_tool_loop_handles_invalid_tool_call_arguments(
        self, agent: LiteLLMAgent
    ) -> None:
        tool_called: list[dict[str, Any]] = []

        def tool_fn(**kwargs: Any) -> str:
            tool_called.append(kwargs)
            return "tool-response"

        prompt = chat_prompt.ChatPrompt(system="s", user="u")
        prompt.tools = [
            {
                "type": "function",
                "function": {
                    "name": "search",
                    "description": "search",
                    "parameters": {"type": "object", "properties": {"q": {}}},
                },
            }
        ]
        prompt.function_map = {"search": tool_fn}

        class _ToolMessage:
            def __init__(self) -> None:
                self.tool_calls = [
                    {
                        "id": "call_1",
                        "function": {"name": "search", "arguments": "{bad json"},
                    }
                ]
                self.content = ""

            def to_dict(self) -> dict[str, Any]:
                return {"tool_calls": self.tool_calls, "content": self.content}

            def __getitem__(self, key: str) -> Any:
                return {"tool_calls": self.tool_calls, "content": self.content}[key]

        class _FinalMessage:
            def __init__(self) -> None:
                self.tool_calls: list[dict[str, Any]] = []
                self.content = "final answer"

            def to_dict(self) -> dict[str, Any]:
                return {"tool_calls": self.tool_calls, "content": self.content}

            def __getitem__(self, key: str) -> Any:
                return {"tool_calls": self.tool_calls, "content": self.content}[key]

        first_response = make_litellm_completion_response(message=_ToolMessage())
        second_response = make_litellm_completion_response(message=_FinalMessage())

        captured_messages: list[list[dict[str, Any]]] = []

        def fake_complete(
            model: str,
            messages: list[dict[str, Any]],
            **kwargs: Any,
        ) -> MagicMock:
            _ = model, kwargs
            captured_messages.append(list(messages))
            return first_response if len(captured_messages) == 1 else second_response

        with patch.object(agent, "_llm_complete", side_effect=fake_complete):
            result = agent.invoke_agent(
                prompts={"p": prompt},
                dataset_item={"input": "x"},
                allow_tool_use=True,
            )

        assert result == "final answer"
        assert tool_called == []
        assert len(captured_messages) == 2
        assert any(
            msg.get("role") == "tool"
            and "Invalid JSON arguments for tool `search`" in str(msg.get("content"))
            for msg in captured_messages[1]
        )


class TestLiteLLMAgentCostTracking:
    """Test cost and usage tracking in LiteLLMAgent."""

    def test_llm_complete_attaches_cost(self, agent: LiteLLMAgent) -> None:
        """Test that _llm_complete attaches cost to response."""
        mock_response = make_litellm_completion_response(
            "response",
            cost=0.005,
            usage={"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150},
        )

        with patch("litellm.completion", return_value=mock_response):
            with patch(
                "opik_optimizer.agents.litellm_agent.track_completion"
            ) as mock_track:
                mock_track.return_value = lambda x: x

                result = agent._llm_complete(
                    model="gpt-4o",
                    messages=[user_message("test")],
                    tools=None,
                )

                assert result._opik_cost == 0.005
                assert result._opik_usage["prompt_tokens"] == 100
                assert result._opik_usage["completion_tokens"] == 50
                assert result._opik_usage["total_tokens"] == 150

    def test_llm_complete_handles_missing_usage(self, agent: LiteLLMAgent) -> None:
        """Test handling of responses without usage data."""
        mock_response = make_litellm_completion_response(
            "response", cost=None, usage=None
        )

        with patch("litellm.completion", return_value=mock_response):
            with patch(
                "opik_optimizer.agents.litellm_agent.track_completion"
            ) as mock_track:
                mock_track.return_value = lambda x: x

                result = agent._llm_complete(
                    model="gpt-4o",
                    messages=[user_message("test")],
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
        mock_response = make_litellm_completion_response(["Choice 1", "Choice 2"])

        with patch.object(agent, "_llm_complete", return_value=mock_response):
            result = agent.invoke_agent(
                prompts={"test": simple_prompt},
                dataset_item={"input": "test"},
            )

            assert "Choice 1" in result
            assert "Choice 2" in result

    def test_invoke_candidates_returns_all_choices(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test invoke_agent_candidates returns each choice separately."""
        mock_response = make_litellm_completion_response(["Choice A", "Choice B"])

        with patch.object(agent, "_llm_complete", return_value=mock_response):
            result = agent.invoke_agent_candidates(
                prompts={"test": simple_prompt},
                dataset_item={"input": "test"},
            )

            assert result == ["Choice A", "Choice B"]

    def test_invoke_with_empty_choices(
        self, agent: LiteLLMAgent, simple_prompt: chat_prompt.ChatPrompt
    ) -> None:
        """Test handling of empty choices list."""
        mock_response = make_litellm_completion_response([])

        with patch.object(agent, "_llm_complete", return_value=mock_response):
            result = agent.invoke_agent(
                prompts={"test": simple_prompt},
                dataset_item={"input": "test"},
            )

            assert result == ""


class TestLiteLLMAgentPrepareMessages:
    """Test message preparation hook."""

    def test_prepare_messages_can_be_overridden(self) -> None:
        """Test that subclass can override _prepare_messages."""

        class CustomAgent(LiteLLMAgent):
            def _prepare_messages(
                self,
                messages: list[dict[str, Any]],
                dataset_item: dict[str, Any] | None,
            ) -> list[dict[str, Any]]:
                return messages + [user_message("extra")]

        agent = CustomAgent(project_name="test")
        messages = [user_message("original")]
        result = agent._prepare_messages(messages, None)

        assert len(result) == 2
        assert result[1]["content"] == "extra"
