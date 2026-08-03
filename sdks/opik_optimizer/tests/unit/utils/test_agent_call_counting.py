# mypy: disable-error-code=no-untyped-def
"""OPIK-7521: the agent's LLM-call counter must survive the worker pool.

Evaluation dispatches agent calls onto a thread pool (the GEPA adapter passes
num_threads=n_threads, default 12). Counting used to go through a call-stack
walk for the owning optimizer, and a worker thread's stack does not contain the
optimizer's frames — so every threaded call was silently uncounted and the run
reported a call count far below what it actually spent.
"""

from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch


def _fake_llm_complete(self, model, messages, tools, seed, model_kwargs=None):
    return SimpleNamespace(
        choices=[
            SimpleNamespace(message=SimpleNamespace(content="ok", tool_calls=None))
        ]
    )


def _agent_with_owner() -> tuple[Any, Any, Any]:
    from opik_optimizer import ChatPrompt, GepaOptimizer
    from opik_optimizer.agents.litellm_agent import LiteLLMAgent

    optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0)
    prompt = ChatPrompt(
        messages=[{"role": "user", "content": "hi"}], model="gpt-4o-mini"
    )
    agent = LiteLLMAgent(project_name="test")
    optimizer._attach_agent_owner(agent)
    return optimizer, agent, prompt


def test_agent_calls_on_worker_threads_are_counted() -> None:
    from opik_optimizer.agents.litellm_agent import LiteLLMAgent

    optimizer, agent, prompt = _agent_with_owner()

    def one_call() -> None:
        agent._run_completion(prompt=prompt, messages=[], tools=None, seed=1)

    with patch.object(LiteLLMAgent, "_llm_complete", _fake_llm_complete):
        with ThreadPoolExecutor(max_workers=4) as executor:
            list(executor.map(lambda _: one_call(), range(4)))

    assert optimizer.llm_call_counter == 4


def test_owner_is_resolved_under_either_attribute_name() -> None:
    """_attach_agent_owner sets both `optimizer` and `_optimizer_owner`, and the
    two were read in different places — a caller setting only the historical
    name lost the counters."""
    from opik_optimizer.agents.litellm_agent import LiteLLMAgent

    optimizer, agent, prompt = _agent_with_owner()
    agent.optimizer = None  # leave only the legacy attribute set

    with patch.object(LiteLLMAgent, "_llm_complete", _fake_llm_complete):
        agent._run_completion(prompt=prompt, messages=[], tools=None, seed=1)

    assert optimizer.llm_call_counter == 1
