from ...api_objects import chat_prompt
from typing import Any
import rapidfuzz
import copy


def get_task_description_for_llm(prompt: chat_prompt.ChatPrompt) -> str:
    """Generates a concise task description for LLM prompts that need context."""
    description = "Task: Given a list of AI messages with placeholder values, generate an effective prompt. "
    description += f"The original high-level instruction being optimized is: '{prompt.get_messages()}'. "
    description += "The goal is to create an effective prompt that guides a language model to perform this task well."
    return description


def calculate_population_diversity(population: list[Any] | None) -> float:
    """Calculate the diversity of the current population."""
    if not population:
        return 0.0

    # Calculate average Levenshtein using rapidfuzz distance between all pairs
    total_distance = 0.0
    count = 0
    for i in range(len(population)):
        for j in range(i + 1, len(population)):
            str1 = str(population[i])
            str2 = str(population[j])
            distance = rapidfuzz.distance.Indel.normalized_similarity(str1, str2)
            max_len = max(len(str1), len(str2))
            if max_len > 0:
                normalized_distance = distance / max_len
                total_distance += normalized_distance
                count += 1

    return total_distance / count if count > 0 else 0.0


def update_individual_with_prompt(
    individual: Any, prompt_candidate: chat_prompt.ChatPrompt
) -> Any:
    individual[:] = prompt_candidate.get_messages()
    setattr(individual, "tools", copy.deepcopy(prompt_candidate.tools))
    setattr(individual, "function_map", prompt_candidate.function_map)
    return individual
