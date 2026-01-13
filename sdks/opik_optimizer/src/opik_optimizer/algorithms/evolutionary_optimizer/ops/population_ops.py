from typing import Any

import json
import logging

from deap import tools
from deap import creator as _creator

from .. import reporting, helpers, evolutionary_optimizer  # noqa: F401
from ....api_objects import chat_prompt
from .... import utils, _llm_calls
from ....utils.prompt_library import PromptLibrary


logger = logging.getLogger(__name__)
creator = _creator


def restart_population(
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    hof: tools.HallOfFame,
    population: list[Any],
    best_prompts_so_far: dict[str, chat_prompt.ChatPrompt],
) -> list[Any]:
    """Return a fresh, evaluated population seeded by elites.

    Args:
        optimizer: The evolutionary optimizer instance.
        hof: Hall of fame containing best individuals.
        population: Current population.
        best_prompts_so_far: Dict mapping prompt names to best ChatPrompt objects.

    Returns:
        A new evaluated population with variations of the seed prompts.
    """
    # Get best elite to use as seed
    if optimizer.enable_moo:
        elites = list(hof)
    else:
        elites = tools.selBest(population, optimizer.elitism_size)

    if elites:
        best_elite = max(elites, key=lambda x: x.fitness.values[0])
        seed_prompts = optimizer._individual_to_prompts(best_elite)
    else:
        seed_prompts = best_prompts_so_far

    # Generate variations per prompt (same pattern as initial population)
    prompt_variations: dict[str, list[chat_prompt.ChatPrompt]] = {}
    for prompt_name, prompt_obj in seed_prompts.items():
        variations = initialize_population(
            prompt=prompt_obj,
            output_style_guidance=optimizer.output_style_guidance,
            model=optimizer.model,
            model_parameters=optimizer.model_parameters,
            optimization_id=optimizer.current_optimization_id,
            population_size=optimizer.population_size,
            verbose=optimizer.verbose,
            prompts=optimizer._prompts,
        )
        prompt_variations[prompt_name] = variations

    # Combine variations into individuals
    new_pop = []
    for i in range(optimizer.population_size):
        prompts_for_individual = {
            name: variations[i % len(variations)]
            for name, variations in prompt_variations.items()
        }
        new_pop.append(
            optimizer._create_individual_from_prompts(prompts_for_individual)
        )

    # Evaluate the new population
    for ind, fit in zip(
        new_pop, map(optimizer._deap_evaluate_individual_fitness, new_pop)
    ):
        ind.fitness.values = fit

    optimizer._gens_since_pop_improvement = 0
    return new_pop


def initialize_population(
    prompt: chat_prompt.ChatPrompt,
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    prompts: PromptLibrary,
    optimization_id: str | None,
    population_size: int,
    verbose: int,
) -> list[chat_prompt.ChatPrompt]:
    """Initialize the population with diverse variations of the initial prompt,
    including some 'fresh start' prompts based purely on task description.
    All generated prompts should aim to elicit answers matching self.output_style_guidance.
    """
    with reporting.initializing_population(verbose=verbose) as init_pop_report:
        init_pop_report.start(population_size)

        population = [prompt]
        if population_size <= 1:
            return population

        num_to_generate_total = population_size - 1
        num_fresh_starts = max(1, int(num_to_generate_total * 0.2))
        num_variations_on_initial = num_to_generate_total - num_fresh_starts

        task_desc_for_llm = helpers.get_task_description_for_llm(prompt)
        current_output_style_guidance = output_style_guidance

        # Fresh starts
        if num_fresh_starts > 0:
            init_pop_report.start_fresh_prompts(num_fresh_starts)
            fresh_start_user_prompt = prompts.get(
                "fresh_start_user_prompt_template",
                task_description=task_desc_for_llm,
                style=current_output_style_guidance,
                num_to_generate=num_fresh_starts,
            )
            try:
                response_content = _llm_calls.call_model(
                    messages=[
                        {
                            "role": "system",
                            "content": prompts.get(
                                "fresh_start_system_prompt_template",
                                style=current_output_style_guidance,
                            ),
                        },
                        {"role": "user", "content": fresh_start_user_prompt},
                    ],
                    model=model,
                    model_parameters=model_parameters,
                    is_reasoning=True,
                    return_all=_llm_calls.requested_multiple_candidates(
                        model_parameters
                    ),
                )

                response_items = (
                    response_content
                    if isinstance(response_content, list)
                    else [response_content]
                )
                logger.debug(
                    "Raw LLM response for fresh start prompts: %s", response_items
                )

                # Collect prompt lists from each n-choice response to expand candidates.
                parsed_prompts: list[list[dict[str, Any]]] = []
                for response_item in response_items:
                    fresh_prompts = utils.json_to_dict(response_item)
                    if isinstance(fresh_prompts, list):
                        if all(isinstance(p, dict) for p in fresh_prompts) and all(
                            p.get("role") is not None for p in fresh_prompts
                        ):
                            parsed_prompts.append(fresh_prompts)
                        elif all(isinstance(p, list) for p in fresh_prompts):
                            parsed_prompts.extend(fresh_prompts)

                if parsed_prompts:
                    prompts_to_use = parsed_prompts[:num_fresh_starts]
                    population.extend(
                        [
                            chat_prompt.ChatPrompt(
                                messages=p,
                                tools=prompt.tools,
                                function_map=prompt.function_map,
                                model=prompt.model,
                                model_parameters=prompt.model_kwargs,
                            )
                            for p in prompts_to_use
                        ]
                    )
                    init_pop_report.success_fresh_prompts(len(prompts_to_use))
                else:
                    init_pop_report.failed_fresh_prompts(
                        num_fresh_starts,
                        "LLM response for fresh starts was not a valid list of prompts. Skipping fresh start prompts.",
                    )
            except json.JSONDecodeError as e_json:
                init_pop_report.failed_fresh_prompts(
                    num_fresh_starts,
                    f"JSONDecodeError generating fresh start prompts: {e_json}. LLM response: '{response_content}'. Skipping fresh start prompts.",
                )
            except Exception as e:
                init_pop_report.failed_fresh_prompts(
                    num_fresh_starts,
                    f"Error generating fresh start prompts: {e}. Skipping fresh start prompts.",
                )

        # Variations on the initial prompt
        if num_variations_on_initial > 0:
            init_pop_report.start_variations(num_variations_on_initial)
            user_prompt_for_variation = prompts.get(
                "variation_user_prompt_template",
                initial_prompt_messages=prompt.get_messages(),
                task_description=task_desc_for_llm,
                style=current_output_style_guidance,
                num_variations=num_variations_on_initial,
            )
            try:
                response_content_variations = _llm_calls.call_model(
                    messages=[
                        {
                            "role": "system",
                            "content": prompts.get(
                                "variation_system_prompt_template",
                                style=current_output_style_guidance,
                            ),
                        },
                        {"role": "user", "content": user_prompt_for_variation},
                    ],
                    model=model,
                    model_parameters=model_parameters,
                    is_reasoning=True,
                    return_all=_llm_calls.requested_multiple_candidates(
                        model_parameters
                    ),
                )
                response_items = (
                    response_content_variations
                    if isinstance(response_content_variations, list)
                    else [response_content_variations]
                )
                logger.debug(
                    "Raw response for population variations: %s", response_items
                )
                generated_prompts_variations: list[list[dict[str, Any]]] = []
                for response_item in response_items:
                    json_response_variations = json.loads(response_item)
                    generated_prompts_variations.extend(
                        [
                            p["prompt"]
                            for p in json_response_variations.get("prompts", [])
                            if isinstance(p, dict) and "prompt" in p
                        ]
                    )

                if generated_prompts_variations:
                    init_pop_report.success_variations(
                        len(generated_prompts_variations)
                    )
                    population.extend(
                        [
                            chat_prompt.ChatPrompt(
                                messages=p,
                                tools=prompt.tools,
                                function_map=prompt.function_map,
                                model=prompt.model,
                                model_parameters=prompt.model_kwargs,
                            )
                            for p in generated_prompts_variations
                        ]
                    )
                else:
                    init_pop_report.failed_variations(
                        num_variations_on_initial,
                        "Could not parse 'prompts' list for variations. Skipping variations.",
                    )
            except Exception as e:
                init_pop_report.failed_variations(
                    num_variations_on_initial,
                    f"Error calling LLM for initial population variations: {e}",
                )

        # Ensure population is of the required size using unique prompts
        final_population_set: set[str] = set()
        final_population_list: list[chat_prompt.ChatPrompt] = []
        for p in population:
            if json.dumps(p.get_messages()) not in final_population_set:
                final_population_set.add(json.dumps(p.get_messages()))
                final_population_list.append(p)

        init_pop_report.end(final_population_list)
        return final_population_list[:population_size]


def should_restart_population(
    curr_best: float,
    best_primary_score_history: list[float],
    gens_since_pop_improvement: int,
    default_restart_threshold: float,
    default_restart_generations: int,
) -> tuple[bool, int, list[float]]:
    """Update internal counters and decide if we should trigger a population restart."""
    if best_primary_score_history:
        threshold = best_primary_score_history[-1] * (1 + default_restart_threshold)
        if curr_best < threshold:
            gens_since_pop_improvement += 1
        else:
            gens_since_pop_improvement = 0  # type: ignore[attr-defined]

    best_primary_score_history.append(curr_best)
    return (
        gens_since_pop_improvement >= default_restart_generations,
        gens_since_pop_improvement,
        best_primary_score_history,
    )
