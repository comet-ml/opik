"""
Checkpoint helpers for EvolutionaryOptimizer.
"""

from __future__ import annotations

import copy
from typing import Any

from ..optimization_config import chat_prompt


def _serialize_prompt(optimizer: Any, prompt_obj: chat_prompt.ChatPrompt) -> dict[str, Any]:
    return optimizer._serialize_prompt(prompt_obj)


def _deserialize_prompt(
    optimizer: Any, payload: dict[str, Any], base_prompt: chat_prompt.ChatPrompt
) -> chat_prompt.ChatPrompt:
    return optimizer._deserialize_prompt(payload, base_prompt)


def _serialize_individual(optimizer: Any, individual: Any) -> dict[str, Any]:
    messages = [copy.deepcopy(msg) for msg in individual]
    tools = copy.deepcopy(getattr(individual, "tools", None))
    fitness = tuple(individual.fitness.values) if individual.fitness.valid else None
    return {
        "messages": messages,
        "tools": tools,
        "fitness": fitness,
    }


def _deserialize_individual(
    optimizer: Any, payload: dict[str, Any], base_prompt: chat_prompt.ChatPrompt
) -> Any:
    prompt_clone = base_prompt.copy()
    prompt_clone.set_messages(payload["messages"])
    if payload.get("tools") is not None:
        prompt_clone.tools = payload["tools"]
    individual = optimizer._create_individual_from_prompt(prompt_clone)
    if payload.get("fitness") is not None:
        individual.fitness.values = tuple(payload["fitness"])
    return individual


def capture_state(
    optimizer: Any,
    *,
    generation_idx: int,
    trials_used: int,
    max_trials: int,
    best_prompt_overall: chat_prompt.ChatPrompt,
    best_primary_score_overall: float,
    initial_primary_score: float,
    initial_length: float,
    deap_population: list[Any],
    hall_of_fame: Any,
    best_fitness_history: list[float],
    best_primary_score_history: list[float],
    generations_without_improvement: int,
    generations_without_overall_improvement: int,
    gens_since_pop_improvement: int,
) -> dict[str, Any]:
    state: dict[str, Any] = {
        "generation_idx": generation_idx,
        "trials_used": trials_used,
        "max_trials": max_trials,
        "best_prompt_overall": _serialize_prompt(optimizer, best_prompt_overall),
        "best_primary_score_overall": best_primary_score_overall,
        "initial_primary_score": initial_primary_score,
        "initial_length": initial_length,
        "population": [_serialize_individual(optimizer, ind) for ind in deap_population],
        "best_fitness_history": list(best_fitness_history),
        "best_primary_score_history": list(best_primary_score_history),
        "generations_without_improvement": generations_without_improvement,
        "generations_without_overall_improvement": generations_without_overall_improvement,
        "gens_since_pop_improvement": gens_since_pop_improvement,
    }

    hof_entries: list[dict[str, Any]] = []
    if hall_of_fame:
        for ind in hall_of_fame:
            hof_entries.append(_serialize_individual(optimizer, ind))
    state["hall_of_fame"] = hof_entries
    state["enable_moo"] = optimizer.enable_moo
    return state


def restore_state(
    optimizer: Any,
    state: dict[str, Any],
    *,
    base_prompt: chat_prompt.ChatPrompt,
) -> dict[str, Any]:
    best_prompt_overall = _deserialize_prompt(
        optimizer, state["best_prompt_overall"], base_prompt
    )

    population = [
        _deserialize_individual(optimizer, payload, base_prompt)
        for payload in state.get("population", [])
    ]

    hof_entries = state.get("hall_of_fame", [])
    restored_hof = []
    for payload in hof_entries:
        restored_hof.append(_deserialize_individual(optimizer, payload, base_prompt))

    return {
        "generation_idx": state.get("generation_idx", 0),
        "trials_used": state.get("trials_used", 0),
        "max_trials": state.get("max_trials"),
        "best_prompt_overall": best_prompt_overall,
        "best_primary_score_overall": state.get("best_primary_score_overall", 0.0),
        "initial_primary_score": state.get("initial_primary_score", 0.0),
        "initial_length": state.get("initial_length", 0.0),
        "population": population,
        "hall_of_fame": restored_hof,
        "best_fitness_history": state.get("best_fitness_history", []),
        "best_primary_score_history": state.get("best_primary_score_history", []),
        "generations_without_improvement": state.get("generations_without_improvement", 0),
        "generations_without_overall_improvement": state.get(
            "generations_without_overall_improvement", 0
        ),
        "gens_since_pop_improvement": state.get("gens_since_pop_improvement", 0),
    }
