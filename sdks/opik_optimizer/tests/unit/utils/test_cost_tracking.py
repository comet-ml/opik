import types

import pytest

from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.api_objects import chat_prompt


class DummyOptimizer(BaseOptimizer):
    """Minimal optimizer to track cost/usage callbacks."""

    def __init__(self) -> None:
        super().__init__(model="dummy", verbose=0)

    def optimize_prompt(self, *args, **kwargs):
        raise NotImplementedError("not used in this test")


class DummyResponse:
    """LiteLLM-style response stub."""

    class _Choice:
        def __init__(self, content: str):
            self.message = types.SimpleNamespace(content=content)

    class _Usage:
        def __init__(self):
            self.prompt_tokens = 3
            self.completion_tokens = 5
            self.total_tokens = 8

    def __init__(self, content: str, cost: float | None):
        self.choices = [self._Choice(content)]
        self.cost = cost
        self.usage = self._Usage()
        # populated by agent
        self._opik_cost = cost
        self._opik_usage = {
            "prompt_tokens": self.usage.prompt_tokens,
            "completion_tokens": self.usage.completion_tokens,
            "total_tokens": self.usage.total_tokens,
        }


@pytest.fixture
def dummy_prompt() -> chat_prompt.ChatPrompt:
    return chat_prompt.ChatPrompt(
        name="t",
        messages=[{"role": "user", "content": "hello"}],
        model="dummy-model",
    )


def test_apply_cost_usage_to_owner_updates_optimizer():
    opt = DummyOptimizer()
    agent = LiteLLMAgent(project_name="test")
    agent._optimizer_owner = opt
    resp = DummyResponse("hi", cost=1.5)

    agent._apply_cost_usage_to_owner(resp)

    assert opt.llm_cost_total == 1.5
    assert opt.llm_token_usage_total["prompt_tokens"] == 3
    assert opt.llm_token_usage_total["completion_tokens"] == 5
    assert opt.llm_token_usage_total["total_tokens"] == 8


def test_invoke_agent_accumulates_cost(monkeypatch, dummy_prompt):
    opt = DummyOptimizer()
    agent = LiteLLMAgent(project_name="test")
    agent._optimizer_owner = opt

    def fake_complete(*args, **kwargs):
        return DummyResponse("hi there", cost=2.0)

    monkeypatch.setattr(agent, "_llm_complete", fake_complete)

    result = agent.invoke_agent({"p": dummy_prompt}, dataset_item={})

    assert "hi there" in result
    assert opt.llm_cost_total == 2.0
    assert opt.llm_token_usage_total["total_tokens"] == 8
