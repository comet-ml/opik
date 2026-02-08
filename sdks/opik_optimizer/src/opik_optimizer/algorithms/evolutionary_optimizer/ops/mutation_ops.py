from typing import Any, Protocol
from collections.abc import Sequence

import copy
import json
import logging
import random
import sys
import types

from deap import creator as _creator

from ....api_objects import chat_prompt
from ....api_objects.types import (
    Content,
    extract_text_from_content,
    rebuild_content_with_new_text,
)
from ....core import llm_calls as _llm_calls
from .. import helpers
from opik_optimizer.utils.display import display_error, display_success
from ....utils.prompt_roles import apply_role_constraints, count_disallowed_role_updates
from ....utils.prompt_library import PromptLibrary
from . import tool_ops
from ..types import MutationResponse


logger = logging.getLogger(__name__)
creator = _creator


class RandomLike(Protocol):
    def random(self) -> float: ...

    def randint(self, a: int, b: int) -> int: ...

    def sample(self, seq: Sequence[Any], k: int) -> list[Any]: ...

    def choice(self, seq: Sequence[Any]) -> Any: ...

    def shuffle(self, seq: list[Any]) -> None: ...


_reporting_module: Any = types.ModuleType(__name__ + ".reporting")
_reporting_module.display_error = display_error
_reporting_module.display_success = display_success
sys.modules[_reporting_module.__name__] = _reporting_module
reporting = _reporting_module


def compute_adaptive_mutation_rate(
    *,
    current_rate: float,
    best_fitness_history: list[float],
    current_population: list[Any],
    generations_without_improvement: int,
    adaptive_mutation: bool,
    restart_threshold: float,
    restart_generations: int,
    min_rate: float,
    max_rate: float,
    diversity_threshold: float,
) -> tuple[float, int]:
    if not adaptive_mutation or len(best_fitness_history) < 2:
        return current_rate, generations_without_improvement

    previous_best = best_fitness_history[-2]
    recent_improvement = (
        (best_fitness_history[-1] - previous_best) / abs(previous_best)
        if previous_best != 0
        else 0.0
    )
    current_diversity = helpers.calculate_population_diversity(current_population)

    if recent_improvement < restart_threshold:
        generations_without_improvement += 1
    else:
        generations_without_improvement = 0

    base_rate = current_rate
    diversity_bonus = 0.0
    if current_diversity < diversity_threshold:
        diversity_gap = diversity_threshold - current_diversity
        diversity_bonus = diversity_gap * 0.5
        logger.debug(
            "Low diversity detected (%.3f), diversity_bonus=%.3f",
            current_diversity,
            diversity_bonus,
        )

    if generations_without_improvement >= restart_generations:
        adjusted_rate = min(base_rate * 2.5 + diversity_bonus, max_rate)
    elif recent_improvement < 0.01 and current_diversity < diversity_threshold:
        adjusted_rate = min(base_rate * 2.0 + diversity_bonus, max_rate)
    elif recent_improvement < 0.01:
        adjusted_rate = min(base_rate * 1.5 + diversity_bonus * 0.5, max_rate)
    elif recent_improvement > 0.05:
        adjusted_rate = max(base_rate * 0.8 + diversity_bonus * 0.3, min_rate)
    else:
        adjusted_rate = min(base_rate + diversity_bonus, max_rate)

    if adjusted_rate != base_rate:
        logger.debug(
            "Adaptive mutation: base=%.3f adjusted=%.3f diversity=%.3f",
            base_rate,
            adjusted_rate,
            current_diversity,
        )

    return adjusted_rate, generations_without_improvement


def _get_synonym(
    word: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
) -> str:
    """Get a synonym for a word using LLM."""
    try:
        response = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": prompts.get("synonyms_system_prompt"),
                },
                {
                    "role": "user",
                    "content": (
                        f"Give me a single synonym for the word '{word}'. Return only the synonym, nothing else."
                    ),
                },
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
            return_all=_llm_calls.requested_multiple_candidates(model_parameters),
        )
        response_item = response[0] if isinstance(response, list) else response
        return response_item.strip()
    except Exception as e:
        logger.warning(f"Error getting synonym for '{word}': {e}")
        return word


def _modify_phrase(
    phrase: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
) -> str:
    """Modify a phrase while preserving meaning using LLM."""
    try:
        response = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": prompts.get("rephrase_system_prompt"),
                },
                {
                    "role": "user",
                    "content": (
                        f"Modify this phrase while keeping the same meaning: '{phrase}'. Return only the modified phrase, nothing else."
                    ),
                },
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
            return_all=_llm_calls.requested_multiple_candidates(model_parameters),
        )
        response_item = response[0] if isinstance(response, list) else response
        return response_item.strip()
    except Exception as e:
        logger.warning(f"Error modifying phrase '{phrase}': {e}")
        return phrase


def _word_level_mutation(
    msg_content: Content,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    rng: RandomLike,
) -> Content:
    """Perform word-level mutation, handling both string and content parts."""
    text = extract_text_from_content(msg_content)
    words = text.split()
    if len(words) <= 1:
        return msg_content

    mutation_type = rng.random()
    if mutation_type < 0.3:
        idx = rng.randint(0, len(words) - 1)
        words[idx] = _get_synonym(
            word=words[idx],
            model=model,
            model_parameters=model_parameters,
            prompts=prompts,
        )
    elif mutation_type < 0.6:
        if len(words) > 2:
            i, j = rng.sample(range(len(words)), 2)
            words[i], words[j] = words[j], words[i]
    else:
        idx = rng.randint(0, len(words) - 1)
        words[idx] = _modify_phrase(
            phrase=words[idx],
            model=model,
            model_parameters=model_parameters,
            prompts=prompts,
        )

    mutated_text = " ".join(words)
    return rebuild_content_with_new_text(msg_content, mutated_text)


def _word_level_mutation_prompt(
    prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    rng: RandomLike,
    allowed_roles: set[str] | None = None,
) -> chat_prompt.ChatPrompt:
    mutated_messages: list[dict[str, Any]] = []
    for message in prompt.get_messages():
        if allowed_roles is not None and message.get("role") not in allowed_roles:
            mutated_messages.append(message)
            continue
        mutated_content = _word_level_mutation(
            msg_content=message["content"],
            model=model,
            model_parameters=model_parameters,
            prompts=prompts,
            rng=rng,
        )
        mutated_messages.append(
            {
                "role": message["role"],
                "content": mutated_content,
            }
        )
    return chat_prompt.ChatPrompt(
        messages=mutated_messages,
        tools=prompt.tools,
        function_map=prompt.function_map,
        model=prompt.model,
        model_parameters=prompt.model_kwargs,
    )


def _structural_mutation(
    prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    rng: RandomLike,
    allowed_roles: set[str] | None = None,
) -> chat_prompt.ChatPrompt:
    """Perform structural mutation (reordering, combining, splitting)."""
    mutated_messages: list[dict[str, Any]] = []

    for message in prompt.get_messages():
        if allowed_roles is not None and message.get("role") not in allowed_roles:
            mutated_messages.append(message)
            continue
        original_content = message["content"]
        role = message["role"]
        text = extract_text_from_content(original_content)

        sentences = [s.strip() for s in text.split(".") if s.strip()]
        if len(sentences) <= 1:
            mutated_content = _word_level_mutation(
                msg_content=original_content,
                model=model,
                model_parameters=model_parameters,
                prompts=prompts,
                rng=rng,
            )
            mutated_messages.append(
                {
                    "role": role,
                    "content": mutated_content,
                }
            )
            continue

        mutation_type = rng.random()
        new_text: str | None = None
        if mutation_type < 0.3:
            rng.shuffle(sentences)
            new_text = ". ".join(sentences) + "."
        elif mutation_type < 0.6:
            if len(sentences) >= 2:
                idx = rng.randint(0, len(sentences) - 2)
                combined = sentences[idx] + " and " + sentences[idx + 1]
                sentences[idx : idx + 2] = [combined]
                new_text = ". ".join(sentences) + "."
        else:
            idx = rng.randint(0, len(sentences) - 1)
            words = sentences[idx].split()
            if len(words) > 3:
                split_point = rng.randint(2, len(words) - 2)
                sentences[idx : idx + 1] = [
                    " ".join(words[:split_point]),
                    " ".join(words[split_point:]),
                ]
                new_text = ". ".join(sentences) + "."

        if new_text is not None:
            mutated_content = rebuild_content_with_new_text(original_content, new_text)
        else:
            mutated_content = original_content
        mutated_messages.append({"role": role, "content": mutated_content})

    return chat_prompt.ChatPrompt(
        messages=mutated_messages,
        tools=prompt.tools,
        function_map=prompt.function_map,
        model=prompt.model,
        model_parameters=prompt.model_kwargs,
    )


def _semantic_mutation(
    prompt: chat_prompt.ChatPrompt,
    initial_prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    verbose: int,
    output_style_guidance: str,
    prompts: PromptLibrary,
    rng: RandomLike,
    optimize_tools: bool = False,
    allowed_roles: set[str] | None = None,
) -> chat_prompt.ChatPrompt:
    """Enhanced semantic mutation with multiple strategies."""
    current_output_style_guidance = output_style_guidance

    if rng.random() < 0.1:
        return _radical_innovation_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model=model,
            model_parameters=model_parameters,
            output_style_guidance=output_style_guidance,
            prompts=prompts,
            optimize_tools=optimize_tools,
            allowed_roles=allowed_roles,
        )

    try:
        strategy = rng.choice(
            [
                "rephrase",
                "simplify",
                "elaborate",
                "restructure",
                "focus",
                "increase_complexity_and_detail",
            ]
        )

        strategy_prompts = {
            key: prompts.get(
                f"mutation_strategy_{key}",
                style=current_output_style_guidance,
            )
            for key in [
                "rephrase",
                "simplify",
                "elaborate",
                "restructure",
                "focus",
                "increase_complexity_and_detail",
            ]
        }
        user_prompt_for_semantic_mutation = prompts.get(
            "semantic_mutation_user_prompt_template",
            prompt_messages=prompt.get_messages(),
            task_description=helpers.get_task_description_for_llm(
                initial_prompt,
                optimize_tools=optimize_tools,
            ),
            style=current_output_style_guidance,
            strategy_instruction=strategy_prompts[strategy],
        )
        response = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": prompts.get(
                        "semantic_mutation_system_prompt_template",
                        style=current_output_style_guidance,
                    ),
                },
                {"role": "user", "content": user_prompt_for_semantic_mutation},
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
            response_model=MutationResponse,
            return_all=_llm_calls.requested_multiple_candidates(model_parameters),
        )

        response_item = response[0] if isinstance(response, list) else response
        try:
            messages = helpers.parse_llm_messages(response_item)
        except Exception as parse_exc:
            raise RuntimeError(
                "Error parsing semantic mutation response as JSON. "
                f"Response: {response_item!r}\nOriginal error: {parse_exc}"
            ) from parse_exc
        constrained_messages = apply_role_constraints(
            prompt.get_messages(), messages, allowed_roles
        )
        dropped = count_disallowed_role_updates(
            prompt.get_messages(), messages, allowed_roles
        )
        if dropped:
            logger.debug(
                "Evolutionary semantic mutation dropped %s update(s) due to optimize_prompt constraints.",
                dropped,
            )
        return chat_prompt.ChatPrompt(
            messages=constrained_messages,
            tools=prompt.tools,
            function_map=prompt.function_map,
            model=prompt.model,
            model_parameters=prompt.model_kwargs,
        )
    except Exception as e:
        reporting.display_error(
            f"      Error in semantic mutation, this is usually a parsing error: {e}",
            verbose=verbose,
        )
        return prompt.copy()


def _radical_innovation_mutation(
    prompt: chat_prompt.ChatPrompt,
    initial_prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    output_style_guidance: str,
    prompts: PromptLibrary,
    optimize_tools: bool = False,
    allowed_roles: set[str] | None = None,
) -> chat_prompt.ChatPrompt:
    """Attempts to generate a significantly improved and potentially very different prompt using an LLM."""
    logger.debug(
        f"Attempting radical innovation for prompt: {json.dumps(prompt.get_messages())[:70]}..."
    )
    task_desc_for_llm = helpers.get_task_description_for_llm(
        initial_prompt,
        optimize_tools=optimize_tools,
    )
    current_output_style_guidance = output_style_guidance

    user_prompt_for_radical_innovation = prompts.get(
        "radical_innovation_user_prompt_template",
        task_description=task_desc_for_llm,
        style=current_output_style_guidance,
        existing_prompt_messages=prompt.get_messages(),
    )
    try:
        new_prompt_str = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": prompts.get(
                        "radical_innovation_system_prompt_template",
                        style=current_output_style_guidance,
                    ),
                },
                {"role": "user", "content": user_prompt_for_radical_innovation},
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
            response_model=MutationResponse,
            return_all=_llm_calls.requested_multiple_candidates(model_parameters),
        )
        response_item = (
            new_prompt_str[0] if isinstance(new_prompt_str, list) else new_prompt_str
        )
        if isinstance(response_item, str):
            logger.info(
                "Radical innovation LLM result (truncated): %s", response_item[:200]
            )
        try:
            new_messages = helpers.parse_llm_messages(response_item)
        except Exception as parse_exc:
            logger.warning(
                f"Failed to parse LLM output in radical innovation mutation for prompt '{json.dumps(prompt.get_messages())[:50]}...'. Output: {response_item[:200]}. Error: {parse_exc}. Returning original."
            )
            return prompt.copy()
        constrained_messages = apply_role_constraints(
            prompt.get_messages(), new_messages, allowed_roles
        )
        dropped = count_disallowed_role_updates(
            prompt.get_messages(), new_messages, allowed_roles
        )
        if dropped:
            logger.debug(
                "Evolutionary radical mutation dropped %s update(s) due to optimize_prompt constraints.",
                dropped,
            )
        return chat_prompt.ChatPrompt(
            messages=constrained_messages,
            tools=prompt.tools,
            function_map=prompt.function_map,
            model=prompt.model,
            model_parameters=prompt.model_kwargs,
        )
    except Exception as e:
        logger.warning(
            f"Radical innovation mutation failed for prompt '{json.dumps(prompt.get_messages())[:50]}...': {e}. Returning original."
        )
        return prompt.copy()


def deap_mutation(
    individual: Any,
    optimizer: Any | None,
    current_population: list[Any] | None,
    output_style_guidance: str,
    initial_prompts: dict[str, chat_prompt.ChatPrompt],
    model: str,
    model_parameters: dict[str, Any],
    diversity_threshold: float,
    optimization_id: str | None,
    verbose: int,
    prompts: PromptLibrary,
    optimize_tools: bool | None = None,
    tool_names: list[str] | None = None,
    metric: Any | None = None,
    allowed_roles: set[str] | None = None,
    rng: random.Random | None = None,
) -> Any:
    """Enhanced mutation operation with multiple strategies.

    Operates on dict-based individuals (prompt_name -> messages).
    Randomly selects ONE prompt to mutate.
    """
    if optimize_tools is None:
        optimize_tools = bool(getattr(optimizer, "_optimize_tools", False))
    if tool_names is None:
        tool_names = getattr(optimizer, "_tool_names", None)
    if metric is None:
        metric = getattr(optimizer, "_evaluation_metric", None)

    if allowed_roles is not None and not allowed_roles:
        return individual
    # Individual is a dict mapping prompt_name -> messages
    prompts_metadata = getattr(individual, "prompts_metadata", {})
    rng = rng or random.Random()
    prompt_names = list(individual.keys())

    # Randomly select ONE prompt to mutate
    prompt_to_mutate = rng.choice(prompt_names)

    # Create mutated data dict
    mutated_data: dict[str, list[dict[str, Any]]] = {}

    for prompt_name in prompt_names:
        messages = individual[prompt_name]
        metadata = prompts_metadata.get(prompt_name, {})

        if prompt_name == prompt_to_mutate:
            # Get the initial prompt for this prompt_name (for context in semantic mutation)
            initial_prompt = initial_prompts.get(prompt_name)
            if initial_prompt is None:
                # Fallback: use first prompt
                initial_prompt = list(initial_prompts.values())[0]

            # Create a ChatPrompt for mutation
            prompt = chat_prompt.ChatPrompt(
                messages=messages,
                tools=metadata.get("tools"),
                function_map=metadata.get("function_map"),
                name=metadata.get("name", prompt_name),
                model=metadata.get("model", getattr(initial_prompt, "model", None)),
                model_parameters=metadata.get(
                    "model_kwargs", getattr(initial_prompt, "model_kwargs", None)
                ),
            )

            # Choose mutation strategy based on current diversity
            diversity = helpers.calculate_population_diversity(current_population)

            # Determine thresholds based on diversity
            if diversity < diversity_threshold:
                # Low diversity - use more aggressive mutations
                semantic_threshold = 0.5
                structural_threshold = 0.8
            else:
                # Good diversity - use more conservative mutations
                semantic_threshold = 0.4
                structural_threshold = 0.7

            mutation_choice = rng.random()

            if mutation_choice > structural_threshold:
                mutated_prompt = _word_level_mutation_prompt(
                    prompt=prompt,
                    model=model,
                    model_parameters=model_parameters,
                    prompts=prompts,
                    rng=rng,
                    allowed_roles=allowed_roles,
                )
                reporting.display_success(
                    f"      Mutation successful for '{prompt_name}', prompt has been edited by randomizing words (word-level mutation).",
                    verbose=verbose,
                )
            elif mutation_choice > semantic_threshold:
                mutated_prompt = _structural_mutation(
                    prompt=prompt,
                    model=model,
                    model_parameters=model_parameters,
                    prompts=prompts,
                    rng=rng,
                    allowed_roles=allowed_roles,
                )
                reporting.display_success(
                    f"      Mutation successful for '{prompt_name}', prompt has been edited by reordering, combining, or splitting sentences (structural mutation).",
                    verbose=verbose,
                )
            else:
                mutated_prompt = _semantic_mutation(
                    prompt=prompt,
                    initial_prompt=initial_prompt,
                    model=model,
                    model_parameters=model_parameters,
                    verbose=verbose,
                    output_style_guidance=output_style_guidance,
                    prompts=prompts,
                    rng=rng,
                    optimize_tools=optimize_tools,
                    allowed_roles=allowed_roles,
                )
                reporting.display_success(
                    f"      Mutation successful for '{prompt_name}', prompt has been edited using an LLM (semantic mutation).",
                    verbose=verbose,
                )

            # Apply tools to mutated prompt if optimizing tools
            if optimize_tools and optimizer is not None:
                mutated_prompt = tool_ops.apply_tool_description_update(
                    optimizer=optimizer,
                    prompt=mutated_prompt,
                    tool_names=tool_names,
                    round_num=0,
                    metric=metric,
                )

            # Final prompt and metadata
            mutated_data[prompt_name] = mutated_prompt.get_messages()
            metadata = dict(metadata)
            metadata["tools"] = mutated_prompt.tools
            metadata["function_map"] = mutated_prompt.function_map
            metadata["model"] = mutated_prompt.model
            metadata["model_kwargs"] = copy.deepcopy(mutated_prompt.model_kwargs)
            prompts_metadata[prompt_name] = metadata
        else:
            # Keep other prompts unchanged
            mutated_data[prompt_name] = copy.deepcopy(messages)

    # Create new Individual
    new_individual = creator.Individual(mutated_data)  # type: ignore[attr-defined]
    setattr(new_individual, "prompts_metadata", copy.deepcopy(prompts_metadata))

    return new_individual
