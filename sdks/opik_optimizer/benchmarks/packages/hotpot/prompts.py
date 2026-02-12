from __future__ import annotations

from opik_optimizer import ChatPrompt

from benchmarks.agents.hotpot_multihop_agent import get_initial_prompts


def build_hotpot_prompts() -> dict[str, ChatPrompt]:
    return get_initial_prompts()
