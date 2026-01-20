"""
Result formatting operations for the Meta-Prompt Optimizer.

This module contains functions for calculating improvements and creating result objects.
"""

from typing import Any
from collections.abc import Sequence

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....core.state import AlgorithmResult
from ....core.results import OptimizationRound, OptimizationTrial


def calculate_improvement(current_score: float, previous_score: float) -> float:
    """Calculate the improvement percentage between scores."""
    return (
        (current_score - previous_score) / previous_score if previous_score > 0 else 0
    )


def create_result(
    optimizer_class_name: str,
    metric: MetricFunction,
    prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    initial_prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    best_score: float,
    initial_score: float,
    rounds: Sequence[OptimizationRound],
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
    rounds_payload = [round_data.to_dict() for round_data in rounds]
    details = {
        "rounds": rounds_payload,
        "total_rounds": len(rounds_payload),
        "metric_name": metric.__name__,
        "model": model,
        "temperature": temperature,
        "trials_requested": trials_requested,
        "trials_completed": trials_completed,
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

    return AlgorithmResult(
        best_prompts=best_prompts,
        best_score=best_score,
        history=list(rounds),
        metadata=details,
    )


def create_round_data(
    *,
    round_num: int,
    current_best_prompt: dict[str, chat_prompt.ChatPrompt],
    current_best_score: float,
    best_prompt_overall: dict[str, chat_prompt.ChatPrompt],
    evaluated_candidates: Sequence[tuple[dict[str, chat_prompt.ChatPrompt], float]],
    previous_best_score: float,
    improvement_this_round: float,
    trial_index: int | None,
) -> OptimizationRound:
    """Create an OptimizationRound with trials that omit trial_index."""
    _ = trial_index
    candidates = [
        {
            "candidate": cand,
            "score": score,
            "extra": {"improvement": improvement_this_round},
        }
        for cand, score in evaluated_candidates
    ]
    trials = [
        OptimizationTrial(
            trial_index=None,
            score=score,
            candidate=cand,
            extras={"improvement": improvement_this_round},
        )
        for cand, score in evaluated_candidates
    ]
    return OptimizationRound(
        round_index=round_num,
        trials=trials,
        best_score=current_best_score,
        best_prompt=best_prompt_overall,
        generated_prompts=candidates,
        extras={
            "current_prompt": current_best_prompt,
            "current_score": current_best_score,
            "improvement": improvement_this_round,
            "previous_best_score": previous_best_score,
        },
    )


def build_algorithm_result(
    *,
    best_prompts: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    best_score: float,
    history: Sequence[OptimizationRound],
    prompts_per_round: int,
    hall_of_fame_size: int,
    use_hall_of_fame: bool,
) -> AlgorithmResult:
    """Build the AlgorithmResult payload for MetaPromptOptimizer."""
    return AlgorithmResult(
        best_prompts=best_prompts
        if isinstance(best_prompts, dict)
        else {"prompt": best_prompts},
        best_score=best_score,
        history=list(history),
        metadata={
            "prompts_per_round": prompts_per_round,
            "hall_of_fame_size": hall_of_fame_size if use_hall_of_fame else 0,
        },
    )
