from __future__ import annotations

import os
from typing import Any
from collections.abc import Callable

os.environ.setdefault("LITELLM_CACHE_TYPE", "memory")

from opik_optimizer.utils.core import create_litellm_agent_class
from opik_optimizer.api_objects.chat_prompt import ChatPrompt

InvokeCallable = Callable[..., str]


def _build_prompt(
    *,
    name: str,
    invoke: InvokeCallable | None,
    model: str | None = None,
    model_kwargs: dict[str, Any] | None = None,
) -> ChatPrompt:
    return ChatPrompt(
        name=name,
        messages=[{"role": "user", "content": "Hello {user}"}],
        invoke=invoke,
        model=model or "gpt-4o-mini",
        model_parameters=model_kwargs or {},
    )


def test_agent_factory_uses_runtime_prompt_invoke() -> None:
    def base_invoke(
        model: str | None,
        messages: list[dict[str, str]],
        tools: Any,
        **_: Any,
    ) -> str:
        return f"base:{model}:{len(messages)}"

    base_prompt = _build_prompt(name="base", invoke=base_invoke, model="gpt-base")
    agent_class = create_litellm_agent_class(base_prompt, optimizer_ref=None)

    runtime_called: dict[str, Any] = {}

    def runtime_invoke(
        model: str | None,
        messages: list[dict[str, str]],
        tools: Any,
        **model_kwargs: Any,
    ) -> str:
        runtime_called["model"] = model
        runtime_called["messages"] = messages
        runtime_called["kwargs"] = model_kwargs
        return "runtime-response"

    runtime_prompt = _build_prompt(
        name="runtime",
        invoke=runtime_invoke,
        model="gpt-runtime",
        model_kwargs={"temperature": 0.2},
    )
    runtime_agent = agent_class(runtime_prompt)

    response = runtime_agent.invoke(
        [{"role": "user", "content": "Hello world"}], seed=123
    )

    assert response == "runtime-response"
    assert runtime_called["model"] == "gpt-runtime"
    assert runtime_called["messages"][0]["content"] == "Hello world"
    assert runtime_called["kwargs"] == {"temperature": 0.2}


def test_agent_factory_clones_prompt_model_kwargs() -> None:
    def runtime_invoke(
        model: str | None,
        messages: list[dict[str, str]],
        tools: Any,
        **model_kwargs: Any,
    ) -> str:
        return repr(model_kwargs)

    prompt = _build_prompt(
        name="clone-check",
        invoke=runtime_invoke,
        model="gpt-clone",
        model_kwargs={"temperature": 0.4},
    )
    agent_class = create_litellm_agent_class(prompt, optimizer_ref=None)

    agent = agent_class(prompt)
    prompt.model_kwargs["temperature"] = 0.9  # Mutate after agent creation

    response = agent.invoke(
        [{"role": "user", "content": "Test"}],
    )

    assert response == "{'temperature': 0.4}"


def test_agent_factory_generates_valid_class_name() -> None:
    prompt = _build_prompt(name="chat-prompt", invoke=None)
    agent_class = create_litellm_agent_class(prompt, optimizer_ref=None)
    assert agent_class.__name__ == "ChatPromptLiteLLMAgent"


def test_agent_factory_increments_optimizer_counter_for_runtime_invoke() -> None:
    class DummyOptimizer:
        def __init__(self) -> None:
            self.count = 0

        def _increment_llm_counter(self) -> None:
            self.count += 1

    def runtime_invoke(
        model: str | None,
        messages: list[dict[str, str]],
        tools: Any,
        **model_kwargs: Any,
    ) -> str:
        return "ok"

    optimizer = DummyOptimizer()
    base_prompt = _build_prompt(name="base", invoke=runtime_invoke)
    agent_class = create_litellm_agent_class(base_prompt, optimizer_ref=optimizer)

    runtime_prompt = _build_prompt(name="runtime", invoke=runtime_invoke)
    agent = agent_class(runtime_prompt)
    agent.invoke([{"role": "user", "content": "hi"}])

    assert optimizer.count == 1
