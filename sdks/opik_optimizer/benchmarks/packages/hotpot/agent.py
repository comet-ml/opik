from __future__ import annotations

from typing import Any

from benchmarks.agents.hotpot_multihop_agent import HotpotMultiHopAgent


def build_hotpot_agent(
    model_name: str,
    model_parameters: dict[str, Any] | None,
) -> HotpotMultiHopAgent:
    return HotpotMultiHopAgent(
        model=model_name,
        model_parameters=model_parameters,
    )
