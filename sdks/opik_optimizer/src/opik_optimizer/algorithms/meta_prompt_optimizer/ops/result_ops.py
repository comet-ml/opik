"""
Result formatting operations for the Meta-Prompt Optimizer.

This module contains functions for calculating improvements and creating result objects.
"""

from typing import Any, cast
from collections.abc import Sequence
import copy

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....base_optimizer import AlgorithmResult
from ....optimization_result import (
    OptimizationRound,
    OptimizationTrial,
)


def calculate_improvement(current_score: float, previous_score: float) -> float:
    """Calculate the improvement percentage between scores."""
    return (
        (current_score - previous_score) / previous_score if previous_score > 0 else 0
    )


def create_round_data(
    round_num: int,
    current_best_prompt: Any,
    current_best_score: float,
    best_prompt_overall: Any,
    evaluated_candidates: list[tuple[Any, float]],
    previous_best_score: float,
    improvement_this_round: float,
    trial_index: int | None = None,
    best_so_far: float | None = None,
    stop_reason: str | None = None,
) -> OptimizationRound:
    """
    Create a normalized round entry using structured models.

    Args:
        round_num: Round number (0-indexed)
        current_best_prompt: Best prompt from this round
        current_best_score: Best score from this round
        best_prompt_overall: Overall best prompt
        evaluated_candidates: List of (prompt, score) tuples
        previous_best_score: Previous round's best score
        improvement_this_round: Improvement in this round

    Returns:
        OptimizationRound representing the round entry
    """
    # For bundle prompts (dict), keep a representative prompt for logging/validation.
    generated_prompts_log: list[dict[str, Any]] = []
    candidates: list[dict[str, Any]] = []
    for prompt, score in evaluated_candidates:
        improvement_vs_prev = calculate_improvement(score, previous_best_score)
        tool_entries: list[Any] = []
        if getattr(prompt, "tools", None):
            tool_entries = copy.deepcopy(list(prompt.tools or []))

        prompt_payload: Any
        if isinstance(prompt, dict):
            prompt_payload = cast(dict[str, chat_prompt.ChatPrompt], prompt)
        else:
            prompt_payload = prompt

        candidate_entry = {
            "prompt": prompt_payload,
            "tools": tool_entries,
            "score": score,
            "improvement": improvement_vs_prev,
        }
        generated_prompts_log.append(candidate_entry)
        candidates.append(
            {
                "id": str(hash(str(prompt_payload))),
                "score": score,
                "prompt": prompt_payload,
            }
        )

    trials: list[OptimizationTrial] = []
    for cand in candidates:
        trials.append(
            OptimizationTrial(
                trial_index=trial_index,
                score=cand.get("score"),
                prompt=cand.get("prompt"),
                extras={"improvement": cand.get("improvement")},
            )
        )

    return OptimizationRound(
        round_index=round_num,
        trials=trials,
        best_score=current_best_score,
        best_prompt=best_prompt_overall,
        candidates=candidates,
        generated_prompts=generated_prompts_log,
        stop_reason=stop_reason,
        extras={"improvement": improvement_this_round, "best_so_far": best_so_far},
    )


def create_result(
    optimizer_class_name: str,
    metric: MetricFunction,
    prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    initial_prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    best_score: float,
    initial_score: float,
    rounds: Sequence[OptimizationRound | dict[str, Any]],
    trials_requested: int | None,
    trials_completed: int | None,
    dataset_id: str | None,
    optimization_id: str | None,
    llm_call_counter: int,
    llm_calls_tools: int,
    model: str | None = None,
    temperature: float | None = None,
    stopped_early: bool | None = None,
    stop_reason: str | None = None,
    stop_reason_details: dict[str, Any] | None = None,
) -> AlgorithmResult:
    """
    Build an AlgorithmResult for meta-prompt flows (legacy helper).
    """
    details = {
        "rounds": rounds,
        "total_rounds": len(rounds),
        "metric_name": metric.__name__,
        "model": model,
        "temperature": temperature,
        "trials_requested": trials_requested,
        "trials_completed": trials_completed,
        "rounds_completed": len(rounds),
        "stopped_early": stopped_early,
        "stop_reason": stop_reason,
        "stop_reason_details": stop_reason_details,
        "optimizer": optimizer_class_name,
        "dataset_id": dataset_id,
        "optimization_id": optimization_id,
        "llm_calls": llm_call_counter,
        "llm_calls_tools": llm_calls_tools,
    }

    best_prompts = prompt if isinstance(prompt, dict) else {"prompt": prompt}

    normalized_history: list[dict[str, Any]] = []
    for entry in rounds:
        if isinstance(entry, OptimizationRound):
            normalized_history.append(entry.to_dict())
        elif isinstance(entry, dict):
            normalized_history.append(entry)

    return AlgorithmResult(
        best_prompts=best_prompts,
        best_score=best_score,
        history=normalized_history,
        metadata=details,
    )
