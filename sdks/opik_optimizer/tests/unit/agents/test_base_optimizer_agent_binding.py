from __future__ import annotations

from typing import Any

import pytest

from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.optimizable_agent import OptimizableAgent
from opik_optimizer.api_objects.chat_prompt import ChatPrompt


class DummyOptimizer(BaseOptimizer):
    def optimize_prompt(  # type: ignore[override]
        self,
        prompt: ChatPrompt,
        dataset: Any,
        metric: Any,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        *args: Any,
        **kwargs: Any,
    ) -> Any:
        raise NotImplementedError


class CustomAgent(OptimizableAgent):
    pass


@pytest.fixture()
def prompt() -> ChatPrompt:
    return ChatPrompt(
        name="test",
        messages=[{"role": "user", "content": "Hello {name}"}],
        model="gpt-4o-mini",
    )


def test_custom_agent_optimizer_binding(prompt: ChatPrompt) -> None:
    custom_agent_class = CustomAgent

    optimizer_one = DummyOptimizer(model="gpt-4o")
    optimizer_one.agent_class = optimizer_one._setup_agent_class(
        prompt, custom_agent_class
    )
    agent_one = optimizer_one._instantiate_agent(prompt)
    assert agent_one.optimizer is optimizer_one

    optimizer_two = DummyOptimizer(model="gpt-4o")
    optimizer_two.agent_class = optimizer_two._setup_agent_class(
        prompt, custom_agent_class
    )
    agent_two = optimizer_two._instantiate_agent(prompt)
    assert agent_two.optimizer is optimizer_two

    # Ensure binding remains per instance and class-level attribute was not mutated
    assert agent_one.optimizer is optimizer_one
    assert getattr(custom_agent_class, "optimizer", None) is None
