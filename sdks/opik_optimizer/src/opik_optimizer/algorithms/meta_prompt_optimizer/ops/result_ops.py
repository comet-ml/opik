"""
Result formatting operations for the Meta-Prompt Optimizer.

This module contains functions for calculating improvements and creating result objects.
"""

from typing import Any, cast
import copy

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
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
    current_best_prompt: Any,
    current_best_score: float,
    best_prompt_overall: Any,
    evaluated_candidates: list[tuple[Any, float]],
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
    # For bundle prompts (dict), keep a representative prompt for logging/validation.
    current_prompt_repr = (
        next(iter(current_best_prompt.values()))
        if isinstance(current_best_prompt, dict) and current_best_prompt
        else current_best_prompt
    )
    best_prompt_repr = (
        next(iter(best_prompt_overall.values()))
        if isinstance(best_prompt_overall, dict) and best_prompt_overall
        else best_prompt_overall
    )

    generated_prompts_log: list[dict[str, Any]] = []
    for prompt, score in evaluated_candidates:
        improvement_vs_prev = calculate_improvement(score, previous_best_score)
        tool_entries: list[Any] = []
        if getattr(prompt, "tools", None):
            tool_entries = copy.deepcopy(list(prompt.tools or []))

        if isinstance(prompt, dict):
            prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
            prompt_payload: Any = {
                name: p.get_messages() for name, p in prompt_dict.items()
            }
        else:
            prompt_payload = prompt.get_messages()

        generated_prompts_log.append(
            {
                "prompt": prompt_payload,
                "tools": tool_entries,
                "score": score,
                "improvement": improvement_vs_prev,
            }
        )

    return OptimizationRound(
        round_number=round_num + 1,
        current_prompt=current_prompt_repr,
        current_score=current_best_score,
        generated_prompts=generated_prompts_log,
        best_prompt=best_prompt_repr,
        best_score=current_best_score,
        improvement=improvement_this_round,
    )


def create_result(
    optimizer_class_name: str,
    metric: MetricFunction,
    prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    initial_prompt: dict[str, chat_prompt.ChatPrompt] | chat_prompt.ChatPrompt,
    best_score: float,
    initial_score: float,
    rounds: list[OptimizationRound],
    dataset_id: str | None,
    optimization_id: str | None,
    llm_call_counter: int,
    tool_call_counter: int,
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

    Returns:
        OptimizationResult object
    """
    details = {
        "rounds": rounds,
        "total_rounds": len(rounds),
        "metric_name": metric.__name__,
    }

    return OptimizationResult(
        optimizer=optimizer_class_name,
        prompt=prompt,
        score=best_score,
        initial_prompt=initial_prompt,
        initial_score=initial_score,
        metric_name=metric.__name__,
        details=details,
        llm_calls=llm_call_counter,
        tool_calls=tool_call_counter,
        dataset_id=dataset_id,
        optimization_id=optimization_id,
    )
