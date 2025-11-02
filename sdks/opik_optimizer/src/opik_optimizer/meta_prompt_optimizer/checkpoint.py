"""
Checkpoint helpers for MetaPromptOptimizer.
"""

from __future__ import annotations

from typing import Any

from ..optimization_config import chat_prompt


def capture_state(
    optimizer: Any,
    *,
    initial_score: float,
    best_score: float,
    best_prompt: chat_prompt.ChatPrompt,
    trials_used: int,
    round_number: int,
    max_trials: int,
    auto_continue: bool,
) -> dict[str, Any]:
    """
    Produce a serializable checkpoint payload.
    """
    return {
        "initial_score": initial_score,
        "best_score": best_score,
        "best_prompt": optimizer._serialize_prompt(best_prompt),
        "trials_used": trials_used,
        "round_number": round_number,
        "max_trials": max_trials,
        "auto_continue": auto_continue,
    }


def restore_state(
    optimizer: Any,
    state: dict[str, Any],
    prompt: chat_prompt.ChatPrompt,
) -> tuple[chat_prompt.ChatPrompt, float, float, int, bool]:
    """
    Rebuild the best prompt and resume metadata from a checkpoint payload.
    """
    best_prompt = optimizer._deserialize_prompt(state["best_prompt"], prompt)
    best_score = state.get("best_score", 0.0)
    initial_score = state.get("initial_score", best_score)
    trials_used = state.get("trials_used", 0)
    auto_continue = state.get("auto_continue", False)
    return best_prompt, best_score, initial_score, trials_used, auto_continue
