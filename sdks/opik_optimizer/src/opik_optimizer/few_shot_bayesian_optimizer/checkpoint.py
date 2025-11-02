"""
Checkpoint helpers for FewShotBayesianOptimizer.
"""

from __future__ import annotations

from typing import Any, Dict

from ..optimization_result import OptimizationResult


def capture_state(
    *,
    template: Dict[str, Any],
    eval_dataset_item_ids: list[str],
    study_payload: dict[str, Any],
    baseline_score: float,
    trials_completed: int,
    max_trials: int,
) -> dict[str, Any]:
    return {
        "template": template,
        "eval_dataset_item_ids": eval_dataset_item_ids,
        "study": study_payload,
        "baseline_score": baseline_score,
        "trials_completed": trials_completed,
        "max_trials": max_trials,
    }


def restore_state(state: dict[str, Any]) -> dict[str, Any]:
    return {
        "template": state.get("template"),
        "eval_dataset_item_ids": state.get("eval_dataset_item_ids", []),
        "study": state.get("study"),
        "baseline_score": state.get("baseline_score", 0.0),
        "trials_completed": state.get("trials_completed", 0),
        "max_trials": state.get("max_trials"),
    }
