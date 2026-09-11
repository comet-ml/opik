from ...api_objects import chat_prompt
from typing import Any
import copy
import rapidfuzz
from ...utils.helpers import json_to_dict
from ...utils.toolcalling.ops import toolcalling as toolcalling_utils
from ...utils.text import normalize_llm_text


# FIXME: Refactor and move to prompts.py and prompt library.
def get_task_description_for_llm(
    prompt: chat_prompt.ChatPrompt, *, optimize_tools: bool = False
) -> str:
    """Generates a concise task description for LLM prompts that need context."""
    description = "Task: Given a list of AI messages with placeholder values, generate an effective prompt. "
    description += f"The original high-level instruction being optimized is: '{prompt.get_messages()}'. "
    if optimize_tools:
        tool_blocks = toolcalling_utils.build_tool_blocks_from_prompt(prompt)
        if tool_blocks:
            description += f" Tooling context:\n{tool_blocks}\n"
    description += "The goal is to create an effective prompt that guides a language model to perform this task well."
    return description


def calculate_population_diversity(population: list[Any] | None) -> float:
    """Calculate the diversity of the current population.

    Works with dict-based individuals by converting to string representation.
    """
    if not population:
        return 0.0

    # Calculate average Levenshtein using rapidfuzz distance between all pairs
    total_distance = 0.0
    count = 0
    for i in range(len(population)):
        for j in range(i + 1, len(population)):
            str1 = (
                str(dict(population[i]))
                if hasattr(population[i], "items")
                else str(population[i])
            )
            str2 = (
                str(dict(population[j]))
                if hasattr(population[j], "items")
                else str(population[j])
            )
            normalized_distance = rapidfuzz.distance.Indel.normalized_distance(
                str1, str2
            )
            if max(len(str1), len(str2)) > 0:
                total_distance += normalized_distance
                count += 1

    return total_distance / count if count > 0 else 0.0


def update_individual_with_prompt(
    individual: Any, prompt_candidate: chat_prompt.ChatPrompt
) -> Any:
    individual[:] = prompt_candidate.get_messages()
    setattr(individual, "tools", copy.deepcopy(prompt_candidate.tools))
    setattr(individual, "function_map", prompt_candidate.function_map)
    setattr(individual, "model", prompt_candidate.model)
    setattr(individual, "model_kwargs", copy.deepcopy(prompt_candidate.model_kwargs))
    return individual


def parse_llm_messages(response_item: Any) -> list[Any]:
    """Normalize LLM responses into a list of message dicts."""
    if hasattr(response_item, "model_dump"):
        response_item = response_item.model_dump()
    if isinstance(response_item, list):
        return response_item
    if isinstance(response_item, dict):
        return response_item.get("messages") or [response_item]
    return json_to_dict(normalize_llm_text(response_item))
