"""
Checkpoint helpers for HierarchicalReflectiveOptimizer.
"""

from __future__ import annotations

import copy
from typing import Any


def capture_state(
    optimizer: Any,
    *,
    iteration: int,
    best_score: float,
    best_prompt_messages: list[dict[str, Any]],
    history: list[dict[str, Any]],
    should_stop: bool,
    trials_used: int,
) -> dict[str, Any]:
    return {
        "iteration": iteration,
        "best_score": best_score,
        "best_prompt_messages": copy.deepcopy(best_prompt_messages),
        "history": copy.deepcopy(history),
        "should_stop": should_stop,
        "trials_used": trials_used,
    }


def restore_state(state: dict[str, Any]) -> dict[str, Any]:
    return {
        "iteration": state.get("iteration", 0),
        "best_score": state.get("best_score", 0.0),
        "best_prompt_messages": copy.deepcopy(
            state.get("best_prompt_messages", [])
        ),
        "history": copy.deepcopy(state.get("history", [])),
        "should_stop": state.get("should_stop", False),
        "trials_used": state.get("trials_used", 0),
    }
