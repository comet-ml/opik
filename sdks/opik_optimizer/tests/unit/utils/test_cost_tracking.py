from __future__ import annotations

from typing import Any, cast
from unittest.mock import MagicMock

import pytest

from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext
from opik_optimizer.core.results import OptimizationResult


class DummyOptimizer(BaseOptimizer):
    """Minimal optimizer to track cost/usage callbacks."""

    def __init__(self) -> None:
        super().__init__(model="dummy", verbose=0)

    def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
        raise NotImplementedError("not used in this test")

    def run_optimization(self, context: OptimizationContext) -> OptimizationResult:
        raise NotImplementedError("not used in this test")

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        return {"optimizer": "DummyOptimizer"}

    def run_tool_invoke(
        self,
        agent: LiteLLMAgent,
        prompt: chat_prompt.ChatPrompt,
    ) -> str:
        """Invoke the agent so tool counters can find optimizer on the call stack."""
        return agent.invoke_agent(
            prompts={prompt.name: prompt},
            dataset_item={"input": "What's the weather?"},
            allow_tool_use=True,
        )


@pytest.fixture
def tool_prompt() -> chat_prompt.ChatPrompt:
    return chat_prompt.ChatPrompt(
        name="tool-prompt",
        model="dummy-model",
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
                        "properties": {"location": {"type": "string"}},
                    },
                },
            }
        ],
        function_map={"get_weather": lambda location: f"Weather in {location}: Sunny"},
        model_parameters={"temperature": 0.2},
    )


def test_apply_cost_usage_to_owner_updates_optimizer() -> None:
    opt = DummyOptimizer()
    agent = LiteLLMAgent(project_name="test")
    cast(Any, agent)._optimizer_owner = opt

    response = MagicMock()
    response._opik_cost = 1.5
    response._opik_usage = {
        "prompt_tokens": 3,
        "completion_tokens": 5,
        "total_tokens": 8,
    }

    agent._apply_cost_usage_to_owner(response)

    assert opt.llm_cost_total == 1.5
    assert opt.llm_token_usage_total["prompt_tokens"] == 3
    assert opt.llm_token_usage_total["completion_tokens"] == 5
    assert opt.llm_token_usage_total["total_tokens"] == 8


def test_invoke_agent_tracks_cost_with_tools_and_model_kwargs(
    tool_prompt: chat_prompt.ChatPrompt,
) -> None:
    """Test tool calling loop with cost tracking and model_kwargs propagation.

    This consolidated test verifies:
    - Tool calling loop executes correctly (call count)
    - Cost and usage are tracked and propagated to optimizer
    - Model parameters from prompt are passed correctly
    - Tool counter is incremented
    """
    opt = DummyOptimizer()
    agent = LiteLLMAgent(project_name="test")
    cast(Any, agent)._optimizer_owner = opt

    tool_calls_data = [
        {
            "id": "call_123",
            "function": {"name": "get_weather", "arguments": '{"location": "NYC"}'},
        }
    ]

    tool_call_msg = MagicMock()
    tool_call_msg.tool_calls = tool_calls_data
    tool_call_msg.__getitem__ = (
        lambda self, key: tool_calls_data if key == "tool_calls" else None
    )
    tool_call_msg.to_dict = lambda: {"role": "assistant", "tool_calls": tool_calls_data}

    tool_call_response = MagicMock()
    tool_call_response.choices = [MagicMock()]
    tool_call_response.choices[0].message = tool_call_msg
    tool_call_response._opik_cost = 1.5
    tool_call_response._opik_usage = {
        "prompt_tokens": 3,
        "completion_tokens": 5,
        "total_tokens": 8,
    }

    final_msg = MagicMock()
    final_msg.tool_calls = None
    final_msg.__getitem__ = (
        lambda self, key: "The weather is Sunny in NYC" if key == "content" else None
    )
    final_msg.to_dict = lambda: {
        "role": "assistant",
        "content": "The weather is Sunny in NYC",
    }

    final_response = MagicMock()
    final_response.choices = [MagicMock()]
    final_response.choices[0].message = final_msg
    final_response._opik_cost = None
    final_response._opik_usage = None

    captured_model_kwargs: list[dict[str, Any] | None] = []
    call_count = 0

    def mock_complete(*args: Any, **kwargs: Any) -> MagicMock:
        nonlocal call_count
        call_count += 1
        captured_model_kwargs.append(kwargs.get("model_kwargs"))
        if call_count == 1:
            return tool_call_response
        return final_response

    agent._llm_complete = mock_complete  # type: ignore[method-assign]

    result = opt.run_tool_invoke(agent, tool_prompt)

    # Verify tool calling loop behavior
    assert "Sunny" in result
    assert call_count == 2, "Should make 2 LLM calls (tool call + final response)"

    # Verify model_kwargs propagation
    assert captured_model_kwargs[0] == {"temperature": 0.2}

    # Verify cost and usage tracking
    assert opt.llm_call_tools_counter >= 1
    assert opt.llm_cost_total == 1.5
    assert opt.llm_token_usage_total["prompt_tokens"] == 3
    assert opt.llm_token_usage_total["completion_tokens"] == 5
    assert opt.llm_token_usage_total["total_tokens"] == 8
