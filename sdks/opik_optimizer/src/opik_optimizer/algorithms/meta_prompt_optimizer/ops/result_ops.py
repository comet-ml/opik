"""
Result formatting operations for the Meta-Prompt Optimizer.

This module contains functions for calculating improvements and creating result objects.
"""

from typing import Any
from collections.abc import Callable
import copy

from ....api_objects import chat_prompt
from ....optimization_result import OptimizationResult
from ....base_optimizer import OptimizationRound


def calculate_improvement(current_score: float, previous_score: float) -> float:
    """
    Calculate the improvement percentage between scores.

    Args:
        current_score: Current score
        previous_score: Previous score

    Returns:
        Improvement percentage
    """
    return (
        (current_score - previous_score) / previous_score if previous_score > 0 else 0
    )


def create_round_data(
    round_num: int,
    current_best_prompt: chat_prompt.ChatPrompt,
    current_best_score: float,
    best_prompt_overall: chat_prompt.ChatPrompt,
    evaluated_candidates: list[tuple[chat_prompt.ChatPrompt, float]],
    previous_best_score: float,
    improvement_this_round: float,
) -> OptimizationRound:
    """
    Create an OptimizationRound object with the current round's data.

    Args:
        round_num: Round number (0-indexed)
        current_best_prompt: Best prompt from this round
        current_best_score: Best score from this round
        best_prompt_overall: Overall best prompt
        evaluated_candidates: List of (prompt, score) tuples
        previous_best_score: Previous round's best score
        improvement_this_round: Improvement in this round

    Returns:
        OptimizationRound object
    """
    generated_prompts_log: list[dict[str, Any]] = []
    for prompt, score in evaluated_candidates:
        improvement_vs_prev = calculate_improvement(score, previous_best_score)
        tool_entries: list[Any] = []
        if getattr(prompt, "tools", None):
            tool_entries = copy.deepcopy(list(prompt.tools or []))

        generated_prompts_log.append(
            {
                "prompt": prompt.get_messages(),
                "tools": tool_entries,
                "score": score,
                "improvement": improvement_vs_prev,
            }
        )

    return OptimizationRound(
        round_number=round_num + 1,
        current_prompt=current_best_prompt,
        current_score=current_best_score,
        generated_prompts=generated_prompts_log,
        best_prompt=best_prompt_overall,
        best_score=current_best_score,
        improvement=improvement_this_round,
    )


def create_result(
    optimizer_class_name: str,
    metric: Callable,
    initial_prompt: list[dict[str, str]],
    best_prompt: list[dict[str, str]],
    best_score: float,
    initial_score: float,
    rounds: list[OptimizationRound],
    dataset_id: str | None,
    optimization_id: str | None,
    best_tools: list[dict[str, Any]] | None,
    llm_call_counter: int,
    tool_call_counter: int,
    model: str,
    model_parameters: dict[str, Any],
    extract_tool_prompts_fn: Callable,
) -> OptimizationResult:
    """
    Create the final OptimizationResult object.

    Args:
        optimizer_class_name: Name of the optimizer class
        metric: Metric function
        initial_prompt: Initial prompt messages
        best_prompt: Best prompt messages
        best_score: Best score achieved
        initial_score: Initial score
        rounds: List of optimization rounds
        dataset_id: Optional dataset ID
        optimization_id: Optional optimization ID
        best_tools: Optional list of tools
        llm_call_counter: Count of LLM calls
        tool_call_counter: Count of tool calls
        model: Model name
        model_parameters: Model parameters
        extract_tool_prompts_fn: Function to extract tool prompts

    Returns:
        OptimizationResult object
    """
    details = {
        "final_prompt": best_prompt,
        "final_score": best_score,
        "rounds": rounds,
        "total_rounds": len(rounds),
        "metric_name": getattr(metric, "__name__", str(metric)),
        "model": model,
        "temperature": model_parameters.get("temperature"),
    }

    if best_tools:
        details["final_tools"] = best_tools

    tool_prompts = extract_tool_prompts_fn(best_tools)

    return OptimizationResult(
        optimizer=optimizer_class_name,
        prompt=best_prompt,
        score=best_score,
        initial_prompt=initial_prompt,
        initial_score=initial_score,
        metric_name=getattr(metric, "__name__", str(metric)),
        details=details,
        llm_calls=llm_call_counter,
        tool_calls=tool_call_counter,
        dataset_id=dataset_id,
        optimization_id=optimization_id,
        tool_prompts=tool_prompts,
    )
