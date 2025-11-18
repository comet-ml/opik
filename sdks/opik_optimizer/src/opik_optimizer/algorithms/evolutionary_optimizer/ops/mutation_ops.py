from typing import Any, cast

import json
import logging
import random

from .. import prompts as evo_prompts
from ....api_objects import chat_prompt
from .... import utils, _llm_calls
from .. import reporting, helpers, mcp


logger = logging.getLogger(__name__)


def _get_synonym(word: str, model: str, model_parameters: dict[str, Any]) -> str:
    """Get a synonym for a word using LLM."""
    try:
        response = _llm_calls.call_model(
            messages=[
                {"role": "system", "content": evo_prompts.synonyms_system_prompt()},
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
        )
        return response.strip()
    except Exception as e:
        logger.warning(f"Error getting synonym for '{word}': {e}")
        return word


def _modify_phrase(phrase: str, model: str, model_parameters: dict[str, Any]) -> str:
    """Modify a phrase while preserving meaning using LLM."""
    try:
        response = _llm_calls.call_model(
            messages=[
                {"role": "system", "content": evo_prompts.rephrase_system_prompt()},
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
        )
        return response.strip()
    except Exception as e:
        logger.warning(f"Error modifying phrase '{phrase}': {e}")
        return phrase


def _word_level_mutation(
    msg_content: str, model: str, model_parameters: dict[str, Any]
) -> str:
    """Perform word-level mutation."""
    words = msg_content.split()
    if len(words) <= 1:
        return msg_content

    mutation_type = random.random()
    if mutation_type < 0.3:
        idx = random.randint(0, len(words) - 1)
        words[idx] = _get_synonym(
            word=words[idx], model=model, model_parameters=model_parameters
        )
    elif mutation_type < 0.6:
        if len(words) > 2:
            i, j = random.sample(range(len(words)), 2)
            words[i], words[j] = words[j], words[i]
    else:
        idx = random.randint(0, len(words) - 1)
        words[idx] = _modify_phrase(
            phrase=words[idx], model=model, model_parameters=model_parameters
        )

    return " ".join(words)


def _word_level_mutation_prompt(
    prompt: chat_prompt.ChatPrompt, model: str, model_parameters: dict[str, Any]
) -> chat_prompt.ChatPrompt:
    mutated_messages: list[dict[str, str]] = []
    for message in prompt.get_messages():
        mutated_messages.append(
            {
                "role": message["role"],
                "content": _word_level_mutation(
                    msg_content=message["content"],
                    model=model,
                    model_parameters=model_parameters,
                ),
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
) -> chat_prompt.ChatPrompt:
    """Perform structural mutation (reordering, combining, splitting)."""
    mutated_messages: list[dict[str, str]] = []

    for message in prompt.get_messages():
        content = message["content"]
        role = message["role"]

        sentences = [s.strip() for s in content.split(".") if s.strip()]
        if len(sentences) <= 1:
            mutated_messages.append(
                {
                    "role": role,
                    "content": _word_level_mutation(
                        msg_content=content,
                        model=model,
                        model_parameters=model_parameters,
                    ),
                }
            )
            continue

        mutation_type = random.random()
        if mutation_type < 0.3:
            random.shuffle(sentences)
            mutated_messages.append(
                {"role": role, "content": ". ".join(sentences) + "."}
            )
            continue
        elif mutation_type < 0.6:
            if len(sentences) >= 2:
                idx = random.randint(0, len(sentences) - 2)
                combined = sentences[idx] + " and " + sentences[idx + 1]
                sentences[idx : idx + 2] = [combined]
                mutated_messages.append(
                    {"role": role, "content": ". ".join(sentences) + "."}
                )
                continue
        else:
            idx = random.randint(0, len(sentences) - 1)
            words = sentences[idx].split()
            if len(words) > 3:
                split_point = random.randint(2, len(words) - 2)
                sentences[idx : idx + 1] = [
                    " ".join(words[:split_point]),
                    " ".join(words[split_point:]),
                ]
                mutated_messages.append(
                    {"role": role, "content": ". ".join(sentences) + "."}
                )
                continue
            else:
                mutated_messages.append({"role": role, "content": content})

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

        strategy_prompts = evo_prompts.mutation_strategy_prompts(
            current_output_style_guidance
        )
        user_prompt_for_semantic_mutation = evo_prompts.semantic_mutation_user_prompt(
            prompt_messages=prompt.get_messages(),
            task_description=helpers.get_task_description_for_llm(initial_prompt),
            output_style_guidance=current_output_style_guidance,
            strategy_instruction=strategy_prompts[strategy],
        )
        response = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": evo_prompts.semantic_mutation_system_prompt(
                        current_output_style_guidance
                    ),
                },
                {"role": "user", "content": user_prompt_for_semantic_mutation},
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
        )
        response = cast(str, response)

        try:
            messages = utils.json_to_dict(response.strip())
        except Exception as parse_exc:
            raise RuntimeError(
                f"Error parsing semantic mutation response as JSON. "
                f"Response: {response!r}\nOriginal error: {parse_exc}"
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
) -> chat_prompt.ChatPrompt:
    """Attempts to generate a significantly improved and potentially very different prompt using an LLM."""
    logger.debug(
        f"Attempting radical innovation for prompt: {json.dumps(prompt.get_messages())[:70]}..."
    )
    task_desc_for_llm = helpers.get_task_description_for_llm(initial_prompt)
    current_output_style_guidance = output_style_guidance

    user_prompt_for_radical_innovation = evo_prompts.radical_innovation_user_prompt(
        task_desc_for_llm, current_output_style_guidance, prompt.get_messages()
    )
    try:
        new_prompt_str = _llm_calls.call_model(
            messages=[
                {
                    "role": "system",
                    "content": evo_prompts.radical_innovation_system_prompt(
                        current_output_style_guidance
                    ),
                },
                {"role": "user", "content": user_prompt_for_radical_innovation},
            ],
            model=model,
            model_parameters=model_parameters,
            is_reasoning=True,
        )
        logger.info(
            f"Radical innovation LLM result (truncated): {new_prompt_str[:200]}"
        )
        try:
            new_messages = utils.json_to_dict(new_prompt_str)
        except Exception as parse_exc:
            logger.warning(
                f"Failed to parse LLM output in radical innovation mutation for prompt '{json.dumps(prompt.get_messages())[:50]}...'. Output: {new_prompt_str[:200]}. Error: {parse_exc}. Returning original."
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
    initial_prompt: chat_prompt.ChatPrompt,
    model: str,
    model_parameters: dict[str, Any],
    diversity_threshold: float,
    mcp_context: mcp.EvolutionaryMCPContext | None,
    optimization_id: str | None,
    verbose: int,
) -> Any:
    """Enhanced mutation operation with multiple strategies."""
    prompt = chat_prompt.ChatPrompt(
        messages=individual,
        tools=initial_prompt.tools,
        function_map=initial_prompt.function_map,
    )

    mcp_context = mcp_context
    if mcp_context is not None:
        mutated_prompt = mcp.tool_description_mutation(
            prompt=prompt,
            context=mcp_context,
            model=model,
            model_parameters=model_parameters,
            optimization_id=optimization_id,
        )
        if mutated_prompt is not None:
            reporting.display_success(
                "      Mutation successful, tool description updated (MCP mutation).",
                verbose=verbose,
            )
            return helpers.update_individual_with_prompt(individual, mutated_prompt)

    # Choose mutation strategy based on current diversity
    diversity = helpers.calculate_population_diversity(current_population)

    # Determine thresholds based on diversity
    if diversity < diversity_threshold:
        # Low diversity - use more aggressive mutations (higher chance for semantic)
        semantic_threshold = 0.5
        structural_threshold = 0.8  # semantic_threshold + 0.3
    else:
        # Good diversity - use more conservative mutations (higher chance for word_level)
        semantic_threshold = 0.4
        structural_threshold = 0.7  # semantic_threshold + 0.3

    mutation_choice = random.random()

    if mutation_choice > structural_threshold:
        mutated_prompt = _word_level_mutation_prompt(
            prompt=prompt, model=model, model_parameters=model_parameters
        )
        reporting.display_success(
            "      Mutation successful, prompt has been edited by randomizing words (word-level mutation).",
            verbose=verbose,
        )
        return helpers.update_individual_with_prompt(individual, mutated_prompt)
    elif mutation_choice > semantic_threshold:
        mutated_prompt = _structural_mutation(
            prompt=prompt,
            model=model,
            model_parameters=model_parameters,
        )
        reporting.display_success(
            "      Mutation successful, prompt has been edited by reordering, combining, or splitting sentences (structural mutation).",
            verbose=verbose,
        )
        return helpers.update_individual_with_prompt(individual, mutated_prompt)
    else:
        mutated_prompt = _semantic_mutation(
            prompt=prompt,
            initial_prompt=initial_prompt,
            model=model,
            model_parameters=model_parameters,
            verbose=verbose,
            output_style_guidance=output_style_guidance,
        )
        reporting.display_success(
            "      Mutation successful, prompt has been edited using an LLM (semantic mutation).",
            verbose=verbose,
        )
        return helpers.update_individual_with_prompt(individual, mutated_prompt)
