"""
Checkpoint helpers for GepaOptimizer.
"""

from __future__ import annotations

import copy
from typing import Any


def capture_state(
    optimizer: Any,
    *,
    adapter_state: dict[str, Any] | None,
    best_prompt_system_text: str,
    best_score: float,
    history: list[dict[str, Any]],
    trials_completed: int,
    max_trials: int,
    reflection_enabled: bool,
) -> dict[str, Any]:
    return {
        "adapter_state": copy.deepcopy(adapter_state),
        "best_prompt_system_text": best_prompt_system_text,
        "best_score": best_score,
        "history": copy.deepcopy(history),
        "trials_completed": trials_completed,
        "max_trials": max_trials,
        "reflection_enabled": reflection_enabled,
    }


def restore_state(optimizer: Any, state: dict[str, Any]) -> dict[str, Any]:
    return {
        "adapter_state": copy.deepcopy(state.get("adapter_state")),
        "best_prompt_system_text": state.get("best_prompt_system_text"),
        "best_score": state.get("best_score", 0.0),
        "history": copy.deepcopy(state.get("history", [])),
        "trials_completed": state.get("trials_completed", 0),
        "max_trials": state.get("max_trials"),
        "reflection_enabled": state.get("reflection_enabled", False),
    }
