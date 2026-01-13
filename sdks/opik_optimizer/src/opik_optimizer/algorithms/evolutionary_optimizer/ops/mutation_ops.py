from typing import Any

import copy
import json
import logging
import random

from deap import creator as _creator

from ....api_objects import chat_prompt
from ....api_objects.types import (
    Content,
    extract_text_from_content,
    rebuild_content_with_new_text,
)
from .... import utils, _llm_calls
from .. import reporting, helpers
from ....utils.prompt_library import PromptLibrary


logger = logging.getLogger(__name__)
creator = _creator


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
) -> Content:
    """Perform word-level mutation, handling both string and content parts."""
    text = extract_text_from_content(msg_content)
    words = text.split()
    if len(words) <= 1:
        return msg_content

    mutation_type = random.random()
    if mutation_type < 0.3:
        idx = random.randint(0, len(words) - 1)
        words[idx] = _get_synonym(
            word=words[idx],
            model=model,
            model_parameters=model_parameters,
            prompts=prompts,
        )
    elif mutation_type < 0.6:
        if len(words) > 2:
            i, j = random.sample(range(len(words)), 2)
            words[i], words[j] = words[j], words[i]
    else:
        idx = random.randint(0, len(words) - 1)
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
) -> chat_prompt.ChatPrompt:
    mutated_messages: list[dict[str, Any]] = []
    for message in prompt.get_messages():
        mutated_content = _word_level_mutation(
            msg_content=message["content"],
            model=model,
            model_parameters=model_parameters,
            prompts=prompts,
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
    )


def _structural_mutation(
    prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
) -> chat_prompt.ChatPrompt:
    """Perform structural mutation (reordering, combining, splitting)."""
    mutated_messages: list[dict[str, Any]] = []

    for message in prompt.get_messages():
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
            )
            mutated_messages.append(
                {
                    "role": role,
                    "content": mutated_content,
                }
            )
            continue

        mutation_type = random.random()
        new_text: str | None = None
        if mutation_type < 0.3:
            random.shuffle(sentences)
            new_text = ". ".join(sentences) + "."
        elif mutation_type < 0.6:
            if len(sentences) >= 2:
                idx = random.randint(0, len(sentences) - 2)
                combined = sentences[idx] + " and " + sentences[idx + 1]
                sentences[idx : idx + 2] = [combined]
                new_text = ". ".join(sentences) + "."
        else:
            idx = random.randint(0, len(sentences) - 1)
            words = sentences[idx].split()
            if len(words) > 3:
                split_point = random.randint(2, len(words) - 2)
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
    )


def _semantic_mutation(
    prompt: chat_prompt.ChatPrompt,
    initial_prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    verbose: int,
    output_style_guidance: str,
    prompts: PromptLibrary,
) -> chat_prompt.ChatPrompt:
    """Enhanced semantic mutation with multiple strategies."""
    current_output_style_guidance = output_style_guidance

    if random.random() < 0.1:
        return _radical_innovation_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model=model,
            model_parameters=model_parameters,
            output_style_guidance=output_style_guidance,
            prompts=prompts,
        )

    try:
        strategy = random.choice(
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
            task_description=helpers.get_task_description_for_llm(initial_prompt),
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
            return_all=_llm_calls.requested_multiple_candidates(model_parameters),
        )

        response_item = response[0] if isinstance(response, list) else response
        try:
            if isinstance(response_item, list):
                messages = response_item
            elif isinstance(response_item, dict):
                if "messages" in response_item:
                    messages = response_item["messages"]
                else:
                    messages = [response_item]
            else:
                messages = utils.json_to_dict(response_item.strip())
        except Exception as parse_exc:
            raise RuntimeError(
                "Error parsing semantic mutation response as JSON. "
                f"Response: {response_item!r}\nOriginal error: {parse_exc}"
            ) from parse_exc
        return chat_prompt.ChatPrompt(
            messages=messages,
            tools=prompt.tools,
            function_map=prompt.function_map,
        )
    except Exception as e:
        reporting.display_error(
            f"      Error in semantic mutation, this is usually a parsing error: {e}",
            verbose=verbose,
        )
        return prompt


def _radical_innovation_mutation(
    prompt: chat_prompt.ChatPrompt,
    initial_prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    output_style_guidance: str,
    prompts: PromptLibrary,
) -> chat_prompt.ChatPrompt:
    """Attempts to generate a significantly improved and potentially very different prompt using an LLM."""
    logger.debug(
        f"Attempting radical innovation for prompt: {json.dumps(prompt.get_messages())[:70]}..."
    )
    task_desc_for_llm = helpers.get_task_description_for_llm(initial_prompt)
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
            return_all=_llm_calls.requested_multiple_candidates(model_parameters),
        )
        response_item = (
            new_prompt_str[0] if isinstance(new_prompt_str, list) else new_prompt_str
        )
        logger.info(f"Radical innovation LLM result (truncated): {response_item[:200]}")
        try:
            new_messages = utils.json_to_dict(response_item)
        except Exception as parse_exc:
            logger.warning(
                f"Failed to parse LLM output in radical innovation mutation for prompt '{json.dumps(prompt.get_messages())[:50]}...'. Output: {response_item[:200]}. Error: {parse_exc}. Returning original."
            )
            return prompt
        return chat_prompt.ChatPrompt(
            messages=new_messages,
            tools=prompt.tools,
            function_map=prompt.function_map,
        )
    except Exception as e:
        logger.warning(
            f"Radical innovation mutation failed for prompt '{json.dumps(prompt.get_messages())[:50]}...': {e}. Returning original."
        )
        return prompt


def deap_mutation(
    individual: Any,
    current_population: list[Any] | None,
    output_style_guidance: str,
    initial_prompts: dict[str, chat_prompt.ChatPrompt],
    model: str,
    model_parameters: dict[str, Any],
    diversity_threshold: float,
    optimization_id: str | None,
    verbose: int,
    prompts: PromptLibrary,
) -> Any:
    """Enhanced mutation operation with multiple strategies.

    Operates on dict-based individuals (prompt_name -> messages).
    Randomly selects ONE prompt to mutate.
    """
    # Individual is a dict mapping prompt_name -> messages
    prompts_metadata = getattr(individual, "prompts_metadata", {})
    prompt_names = list(individual.keys())

    # Randomly select ONE prompt to mutate
    prompt_to_mutate = random.choice(prompt_names)

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

            mutation_choice = random.random()

            if mutation_choice > structural_threshold:
                mutated_prompt = _word_level_mutation_prompt(
                    prompt=prompt,
                    model=model,
                    model_parameters=model_parameters,
                    prompts=prompts,
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
                )
                reporting.display_success(
                    f"      Mutation successful for '{prompt_name}', prompt has been edited using an LLM (semantic mutation).",
                    verbose=verbose,
                )

            mutated_data[prompt_name] = mutated_prompt.get_messages()
        else:
            # Keep other prompts unchanged
            mutated_data[prompt_name] = copy.deepcopy(messages)

    # Create new Individual
    new_individual = creator.Individual(mutated_data)  # type: ignore[attr-defined]
    setattr(new_individual, "prompts_metadata", copy.deepcopy(prompts_metadata))

    return new_individual
