# mypy: disable-error-code=no-untyped-def
"""OPIK-7521: the agent's LLM-call counter must survive the worker pool.

Evaluation dispatches agent calls onto a thread pool (the GEPA adapter passes
num_threads=n_threads, default 12). Counting used to go through a call-stack
walk for the owning optimizer, and a worker thread's stack does not contain the
optimizer's frames — so every threaded call was silently uncounted and the run
reported far fewer LLM calls than it made.
"""

from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from typing import Any
from unittest.mock import patch

from opik_optimizer import ChatPrompt
from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from opik_optimizer.base_optimizer import BaseOptimizer
from opik_optimizer.core.state import OptimizationContext


class _CountingOptimizer(BaseOptimizer):
    """Minimal optimizer: only the telemetry surface is exercised here."""

    def __init__(self) -> None:
        super().__init__(model="dummy", verbose=0)

    def optimize_prompt(self, *args: Any, **kwargs: Any) -> Any:
        raise NotImplementedError("not used in this test")

    def run_optimization(self, context: OptimizationContext) -> Any:
        raise NotImplementedError("not used in this test")

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        return {"optimizer": "_CountingOptimizer"}


def _fake_llm_complete(self, model, messages, tools, seed, model_kwargs=None):
    return SimpleNamespace(
        choices=[
            SimpleNamespace(message=SimpleNamespace(content="ok", tool_calls=None))
        ]
    )


_PROMPT = ChatPrompt(messages=[{"role": "user", "content": "hi"}], model="gpt-4o-mini")


def _agent_with_owner() -> tuple[_CountingOptimizer, LiteLLMAgent]:
    optimizer = _CountingOptimizer()
    agent = LiteLLMAgent(project_name="test")
    optimizer._attach_agent_owner(agent)
    return optimizer, agent


def test_agent_calls_on_worker_threads_are_counted() -> None:
    optimizer, agent = _agent_with_owner()

    def one_call() -> None:
        agent._run_completion(prompt=_PROMPT, messages=[], tools=None, seed=1)

    with patch.object(LiteLLMAgent, "_llm_complete", _fake_llm_complete):
        with patch("opik_optimizer.utils.prompt_tracing.attach_span_prompt_payload"):
            with ThreadPoolExecutor(max_workers=4) as executor:
                list(executor.map(lambda _: one_call(), range(4)))

    assert optimizer.llm_call_counter == 4


def test_owner_is_resolved_under_either_attribute_name() -> None:
    """_attach_agent_owner sets both `optimizer` and `_optimizer_owner`, and the
    two were read in different places — a caller setting only the historical
    name lost the counters."""
    optimizer, agent = _agent_with_owner()
    agent.optimizer = None  # leave only the legacy attribute set

    with patch.object(LiteLLMAgent, "_llm_complete", _fake_llm_complete):
        with patch("opik_optimizer.utils.prompt_tracing.attach_span_prompt_payload"):
            agent._run_completion(prompt=_PROMPT, messages=[], tools=None, seed=1)

    assert optimizer.llm_call_counter == 1
