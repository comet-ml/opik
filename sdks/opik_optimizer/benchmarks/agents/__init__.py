"""
Benchmark agents for compound AI systems.
"""

from benchmarks.agents.hotpot_multihop_agent import (
    HotpotMultiHopAgent,
    get_initial_prompts,
)

__all__ = ["HotpotMultiHopAgent", "get_initial_prompts"]
