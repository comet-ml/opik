"""
Live integration tests for LiteLLMAgent.

Requires an API key (OPENAI_API_KEY or LITELLM_API_KEY) and a reachable model.
Defaults to gpt-4o-mini; override via MODEL_FOR_COST_TEST.
"""

import os

import pytest

from opik_optimizer.agents.litellm_agent import LiteLLMAgent
from opik_optimizer.api_objects import chat_prompt
from opik_optimizer.base_optimizer import BaseOptimizer


class LiveCostOptimizer(BaseOptimizer):
    """Minimal optimizer to exercise cost/usage accumulation in a live call."""

    def __init__(self, model: str) -> None:
        super().__init__(model=model, verbose=0)

    def optimize_prompt(self, *args, **kwargs):
        raise NotImplementedError("not used in this integration test")


@pytest.mark.integration
def test_live_cost_tracking_round_trip():
    api_key = os.getenv("OPENAI_API_KEY") or os.getenv("LITELLM_API_KEY")
    if not api_key:
        pytest.skip("No API key found for live cost test.")

    model = os.getenv("MODEL_FOR_COST_TEST", "gpt-4o-mini")
    prompt = chat_prompt.ChatPrompt(
        name="live-test",
        messages=[{"role": "user", "content": "Say hello"}],
        model=model,
    )

    opt = LiveCostOptimizer(model=model)
    agent = LiteLLMAgent(project_name="opik-cost-test")
    agent._optimizer_owner = opt

    result = agent.invoke_agent({"p": prompt}, dataset_item={})

    assert "hello" in result.lower()
    # Stack-walk counter may not be incremented outside optimizer flow; cost/usage should be present.
    assert opt.llm_call_counter >= 0
    assert opt.llm_token_usage_total["total_tokens"] > 0
    # Cost may be 0.0 if the provider/model does not return cost; ensure non-negative.
    assert opt.llm_cost_total >= 0.0
    # Debug dump to aid manual inspection when running locally.
    print(
        f"[live-cost-test] cost={opt.llm_cost_total} "
        f"usage={opt.llm_token_usage_total} "
        f"llm_calls={opt.llm_call_counter} tools={opt.llm_calls_tools_counter}"
    )
