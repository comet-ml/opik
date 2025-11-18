from typing import Any

import json
import logging

from deap import tools
from deap import creator as _creator

from .. import prompts as evo_prompts
from .. import reporting, helpers, mcp, evolutionary_optimizer
from ..mcp import initialize_population_mcp
from ....api_objects import chat_prompt
from .... import utils, _llm_calls


logger = logging.getLogger(__name__)
creator = _creator


# TODO: We should probably remove the "optimizer" parameter
def restart_population(
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    hof: tools.HallOfFame,
    population: list[Any],
    best_prompt_so_far: chat_prompt.ChatPrompt,
) -> list[Any]:
    """Return a fresh, evaluated population seeded by elites."""
    if optimizer.enable_moo:
        elites = list(hof)
    else:
        elites = tools.selBest(population, optimizer.elitism_size)

    if elites:
        best_elite = max(elites, key=lambda x: x.fitness.values[0])
        seed_prompt = chat_prompt.ChatPrompt(
            messages=best_elite,
            tools=getattr(best_elite, "tools", best_prompt_so_far.tools),
            function_map=getattr(
                best_elite, "function_map", best_prompt_so_far.function_map
            ),
        )
    else:
        seed_prompt = best_prompt_so_far

    prompt_variants = initialize_population(
        prompt=seed_prompt,
        output_style_guidance=optimizer.output_style_guidance,
        mcp_context=optimizer._mcp_context,
        model=optimizer.model,
        model_parameters=optimizer.model_parameters,
        optimization_id=optimizer.current_optimization_id,
        population_size=optimizer.population_size,
        verbose=optimizer.verbose,
    )
    new_pop = [optimizer._create_individual_from_prompt(p) for p in prompt_variants]

    for ind, fit in zip(
        new_pop, map(optimizer._deap_evaluate_individual_fitness, new_pop)
    ):
        ind.fitness.values = fit

    optimizer._gens_since_pop_improvement = 0
    return new_pop


def initialize_population(
    prompt: chat_prompt.ChatPrompt,
    output_style_guidance: str,
    mcp_context: mcp.EvolutionaryMCPContext | None,
    model: str,
    model_parameters: dict[str, Any],
    optimization_id: str | None,
    population_size: int,
    verbose: int,
) -> list[chat_prompt.ChatPrompt]:
    """Initialize the population with diverse variations of the initial prompt,
    including some 'fresh start' prompts based purely on task description.
    All generated prompts should aim to elicit answers matching self.output_style_guidance.
    """
    if mcp_context is not None:
        return initialize_population_mcp(
            prompt=prompt,
            context=mcp_context,
            model=model,
            model_parameters=model_parameters,
            optimization_id=optimization_id,
            population_size=population_size,
            verbose=verbose,
        )
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
            fresh_start_user_prompt = evo_prompts.fresh_start_user_prompt(
                task_desc_for_llm, current_output_style_guidance, num_fresh_starts
            )
            try:
                response_content = _llm_calls.call_model(
                    messages=[
                        {
                            "role": "system",
                            "content": evo_prompts.fresh_start_system_prompt(
                                current_output_style_guidance
                            ),
                        },
                        {"role": "user", "content": fresh_start_user_prompt},
                    ],
                    model=model,
                    model_parameters=model_parameters,
                    is_reasoning=True,
                )

                logger.debug(
                    f"Raw LLM response for fresh start prompts: {response_content}"
                )

                fresh_prompts = utils.json_to_dict(response_content)
                if isinstance(fresh_prompts, list):
                    if all(isinstance(p, dict) for p in fresh_prompts) and all(
                        p.get("role") is not None for p in fresh_prompts
                    ):
                        population.append(
                            chat_prompt.ChatPrompt(
                                messages=fresh_prompts,
                                tools=prompt.tools,
                                function_map=prompt.function_map,
                            )
                        )
                        init_pop_report.success_fresh_prompts(1)
                    elif all(isinstance(p, list) for p in fresh_prompts):
                        population.extend(
                            [
                                chat_prompt.ChatPrompt(
                                    messages=p,
                                    tools=prompt.tools,
                                    function_map=prompt.function_map,
                                )
                                for p in fresh_prompts[:num_fresh_starts]
                            ]
                        )
                        init_pop_report.success_fresh_prompts(
                            len(fresh_prompts[:num_fresh_starts])
                        )
                    else:
                        init_pop_report.failed_fresh_prompts(
                            num_fresh_starts,
                            f"LLM response for fresh starts was not a valid list of strings or was empty: {response_content}. Skipping fresh start prompts.",
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
            user_prompt_for_variation = evo_prompts.variation_user_prompt(
                prompt.get_messages(),
                task_desc_for_llm,
                current_output_style_guidance,
                num_variations_on_initial,
            )
            try:
                response_content_variations = _llm_calls.call_model(
                    messages=[
                        {
                            "role": "system",
                            "content": evo_prompts.variation_system_prompt(
                                current_output_style_guidance
                            ),
                        },
                        {"role": "user", "content": user_prompt_for_variation},
                    ],
                    model=model,
                    model_parameters=model_parameters,
                    is_reasoning=True,
                )
                logger.debug(
                    f"Raw response for population variations: {response_content_variations}"
                )
                json_response_variations = json.loads(response_content_variations)
                generated_prompts_variations = [
                    p["prompt"]
                    for p in json_response_variations.get("prompts", [])
                    if isinstance(p, dict) and "prompt" in p
                ]

                if generated_prompts_variations:
                    init_pop_report.success_variations(
                        len(generated_prompts_variations[:num_variations_on_initial])
                    )
                    population.extend(
                        [
                            chat_prompt.ChatPrompt(
                                messages=p,
                                tools=prompt.tools,
                                function_map=prompt.function_map,
                            )
                            for p in generated_prompts_variations[
                                :num_variations_on_initial
                            ]
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
