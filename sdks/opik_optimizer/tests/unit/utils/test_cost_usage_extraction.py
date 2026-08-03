# mypy: disable-error-code=no-untyped-def
"""OPIK-7521 review follow-ups for the spend-accounting helpers.

Each test here pins a way the accounting could report a *wrong number* rather
than fail loudly: a negative provider cost subtracting from the run total, a
usage dict of zeros claiming "the provider reported zero tokens", a mapping
response whose usage was never read, and the counter that silently stops
counting once evaluation moves onto its worker pool.
"""

from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
from typing import Any, cast
from unittest.mock import patch

from opik_optimizer.core import llm_calls, runtime


class _Accumulator:
    """Minimal stand-in for the accumulator surface runtime.* writes to."""

    def __init__(self) -> None:
        self.llm_call_counter = 0
        self.llm_call_tools_counter = 0
        self.llm_cost_total = 0.0
        self.llm_token_usage_total = {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }
        self._llm_cost_recorded = False
        self._llm_usage_recorded = False


class TestCoerceCost:
    def test_negative_cost_is_rejected_not_subtracted(self) -> None:
        """add_llm_cost only adds, so a malformed negative would lower the run
        total and under-report spend."""
        assert llm_calls._coerce_cost(-0.5) is None

        accumulator = _Accumulator()
        runtime.add_llm_cost(cast(Any, accumulator), llm_calls._coerce_cost(-0.5))
        assert accumulator.llm_cost_total == 0.0
        assert runtime.reported_llm_cost(cast(Any, accumulator)) is None

    def test_zero_cost_is_still_a_report(self) -> None:
        """A genuinely free call must read as 0.0, not as 'unavailable'."""
        assert llm_calls._coerce_cost(0) == 0.0

    def test_non_finite_and_bool_are_rejected(self) -> None:
        assert llm_calls._coerce_cost(float("inf")) is None
        assert llm_calls._coerce_cost(float("nan")) is None
        assert llm_calls._coerce_cost(True) is None


class TestExtractResponseUsage:
    def test_unrecognizable_usage_reads_as_unavailable_not_zero(self) -> None:
        """Returning zeros would set _llm_usage_recorded and make the result
        claim the provider reported a zero-token run."""
        response = SimpleNamespace(usage=SimpleNamespace(something_else=1))
        assert llm_calls._extract_response_usage(response) is None

    def test_explicit_zero_tokens_is_a_real_report(self) -> None:
        response = SimpleNamespace(
            usage=SimpleNamespace(prompt_tokens=0, completion_tokens=0, total_tokens=0)
        )
        assert llm_calls._extract_response_usage(response) == {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }

    def test_mapping_shaped_response_and_usage_are_read(self) -> None:
        """Aggregated stream chunks and custom providers hand back mappings."""
        response = {"usage": {"prompt_tokens": 7, "completion_tokens": 3}}
        assert llm_calls._extract_response_usage(response) == {
            "prompt_tokens": 7,
            "completion_tokens": 3,
            "total_tokens": 0,
        }

    def test_partial_usage_keeps_the_reported_fields(self) -> None:
        response = SimpleNamespace(usage={"total_tokens": 10})
        assert llm_calls._extract_response_usage(response) == {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 10,
        }


class TestAgentCountersSurviveWorkerThreads:
    """Evaluation dispatches agent calls onto a worker pool (the GEPA adapter
    passes num_threads=n_threads, default 12). A call-stack walk cannot find the
    optimizer from a worker thread, so counting must go through the explicit
    owner reference the optimizer attaches to the agent."""

    def _agent_with_owner(self) -> tuple[Any, Any, Any]:
        from opik_optimizer import ChatPrompt, GepaOptimizer
        from opik_optimizer.agents.litellm_agent import LiteLLMAgent

        optimizer = GepaOptimizer(model="gpt-4o-mini", verbose=0)
        prompt = ChatPrompt(
            messages=[{"role": "user", "content": "hi"}], model="gpt-4o-mini"
        )
        agent = LiteLLMAgent(project_name="test")
        optimizer._attach_agent_owner(agent)
        return optimizer, agent, prompt

    @staticmethod
    def _fake_llm_complete(self, model, messages, tools, seed, model_kwargs=None):
        response = SimpleNamespace(
            choices=[
                SimpleNamespace(message=SimpleNamespace(content="ok", tool_calls=None))
            ]
        )
        response._opik_cost = 0.001
        response._opik_usage = {
            "prompt_tokens": 7,
            "completion_tokens": 3,
            "total_tokens": 10,
        }
        return response

    def test_calls_made_on_worker_threads_are_still_counted(self) -> None:
        from opik_optimizer.agents.litellm_agent import LiteLLMAgent

        optimizer, agent, prompt = self._agent_with_owner()

        def one_call() -> None:
            agent._run_completion(prompt=prompt, messages=[], tools=None, seed=1)

        with patch.object(LiteLLMAgent, "_llm_complete", self._fake_llm_complete):
            with ThreadPoolExecutor(max_workers=4) as executor:
                list(executor.map(lambda _: one_call(), range(4)))

        # Cost already travelled by the owner reference; the counter must agree
        # with it instead of reporting zero calls behind a non-zero spend.
        assert optimizer.llm_call_counter == 4
        assert optimizer.llm_cost_total == 0.004
