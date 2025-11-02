"""
Checkpoint helpers for ParameterOptimizer.
"""

from __future__ import annotations

from typing import Any, Dict


def capture_state(
    *,
    baseline_score: float,
    history: list[dict[str, Any]],
    stage_records: list[dict[str, Any]],
    search_ranges: dict[str, dict[str, Any]],
    study_payload: dict[str, Any],
    best_score: float,
    best_parameters: dict[str, Any],
    best_model_kwargs: dict[str, Any],
    best_model: str,
    current_stage: str,
    trials_completed: int,
    max_trials: int,
    local_search_scale: float | None,
) -> dict[str, Any]:
    return {
        "baseline_score": baseline_score,
        "history": history,
        "stage_records": stage_records,
        "search_ranges": search_ranges,
        "study": study_payload,
        "best_score": best_score,
        "best_parameters": best_parameters,
        "best_model_kwargs": best_model_kwargs,
        "best_model": best_model,
        "current_stage": current_stage,
        "trials_completed": trials_completed,
        "max_trials": max_trials,
        "local_search_scale": local_search_scale,
    }


def restore_state(state: dict[str, Any]) -> dict[str, Any]:
    return {
        "baseline_score": state.get("baseline_score"),
        "history": state.get("history", []),
        "stage_records": state.get("stage_records", []),
        "search_ranges": state.get("search_ranges", {}),
        "study": state.get("study"),
        "best_score": state.get("best_score"),
        "best_parameters": state.get("best_parameters", {}),
        "best_model_kwargs": state.get("best_model_kwargs", {}),
        "best_model": state.get("best_model"),
        "current_stage": state.get("current_stage", "global"),
        "trials_completed": state.get("trials_completed", 0),
        "max_trials": state.get("max_trials"),
        "local_search_scale": state.get("local_search_scale"),
    }
