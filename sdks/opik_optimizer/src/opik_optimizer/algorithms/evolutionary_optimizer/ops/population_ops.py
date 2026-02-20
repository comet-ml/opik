from typing import Any
from collections.abc import Callable
import math

import json
import logging

try:
    from deap import tools
    from deap import creator as _creator

    _DEAP_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - exercised when DEAP is missing
    tools = None
    _creator = None
    _DEAP_IMPORT_ERROR = exc

from .. import reporting, helpers, evolutionary_optimizer  # noqa: F401
from ....api_objects import chat_prompt
from ....core import runtime
from ....core import llm_calls as _llm_calls
from ....utils.helpers import json_to_dict
from ....utils.prompt_library import PromptLibrary
from ....utils.logging import compact_debug_text, debug_log
from ....utils.text import normalize_llm_text
from . import tool_ops


logger = logging.getLogger(__name__)
creator = _creator


def _require_deap() -> None:
    if tools is None or creator is None:
        raise RuntimeError(
            "DEAP is required for EvolutionaryOptimizer. "
            "Install the optimizer extras that include DEAP."
        ) from _DEAP_IMPORT_ERROR


def restart_population(
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    hof: tools.HallOfFame,
    population: list[Any],
    best_prompts_so_far: dict[str, chat_prompt.ChatPrompt],
) -> list[Any]:
    _require_deap()
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
            optimizer=optimizer,
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
    optimizer: Any | None = None,
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

        optimize_tools = bool(getattr(optimizer, "_optimize_tools", False))
        task_desc_for_llm = helpers.get_task_description_for_llm(
            prompt,
            optimize_tools=optimize_tools,
        )
        current_output_style_guidance = output_style_guidance

        population.extend(
            _generate_fresh_start_prompts(
                init_pop_report=init_pop_report,
                prompt=prompt,
                prompts=prompts,
                task_desc_for_llm=task_desc_for_llm,
                output_style_guidance=current_output_style_guidance,
                model=model,
                model_parameters=model_parameters,
                num_fresh_starts=num_fresh_starts,
                optimizer=optimizer,
            )
        )

        population.extend(
            _generate_variation_prompts(
                init_pop_report=init_pop_report,
                prompt=prompt,
                prompts=prompts,
                task_desc_for_llm=task_desc_for_llm,
                output_style_guidance=current_output_style_guidance,
                model=model,
                model_parameters=model_parameters,
                num_variations_on_initial=num_variations_on_initial,
                optimizer=optimizer,
            )
        )

        final_population_list = _dedupe_population(population)

        init_pop_report.end(final_population_list)
        return final_population_list[:population_size]


def _generate_fresh_start_prompts(
    *,
    init_pop_report: Any,
    prompt: chat_prompt.ChatPrompt,
    prompts: PromptLibrary,
    task_desc_for_llm: str,
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    num_fresh_starts: int,
    optimizer: Any | None = None,
) -> list[chat_prompt.ChatPrompt]:
    if num_fresh_starts <= 0:
        return []

    optimize_tools = bool(getattr(optimizer, "_optimize_tools", False))
    tool_names = getattr(optimizer, "_tool_names", None)
    metric = getattr(optimizer, "_evaluation_metric", None)

    init_pop_report.start_fresh_prompts(num_fresh_starts)
    fresh_start_user_prompt = prompts.get(
        "fresh_start_user_prompt_template",
        task_description=task_desc_for_llm,
        style=output_style_guidance,
        num_to_generate=num_fresh_starts,
    )
    try:
        response_items = _call_and_normalize_model_response(
            messages=[
                {
                    "role": "system",
                    "content": prompts.get(
                        "fresh_start_system_prompt_template",
                        style=output_style_guidance,
                    ),
                },
                {"role": "user", "content": fresh_start_user_prompt},
            ],
            model=model,
            model_parameters=model_parameters,
            debug_tag="population_fresh_start_response",
        )

        parsed_prompts: list[list[dict[str, Any]]] = []
        for response_item in response_items:
            fresh_prompts = json_to_dict(response_item)
            if isinstance(fresh_prompts, list):
                if all(isinstance(p, dict) for p in fresh_prompts) and all(
                    p.get("role") is not None for p in fresh_prompts
                ):
                    parsed_prompts.append(fresh_prompts)
                elif all(isinstance(p, list) for p in fresh_prompts):
                    parsed_prompts.extend(fresh_prompts)

        if not parsed_prompts:
            init_pop_report.failed_fresh_prompts(
                num_fresh_starts,
                "LLM response for fresh starts was not a valid list of prompts. Skipping fresh start prompts.",
            )
            return []

        prompts_to_use = parsed_prompts[:num_fresh_starts]
        for idx, prompt_messages in enumerate(prompts_to_use):
            debug_log(
                "population_fresh_start_prompt",
                index=idx,
                prompt=compact_debug_text(json.dumps(prompt_messages)),
            )
        init_pop_report.success_fresh_prompts(len(prompts_to_use))
        return _messages_to_chat_prompts(
            prompts_to_use,
            prompt,
            optimize_tools=optimize_tools,
            tool_names=tool_names,
            metric=metric,
            optimizer=optimizer,
        )
    except json.JSONDecodeError as exc:
        init_pop_report.failed_fresh_prompts(
            num_fresh_starts,
            f"JSONDecodeError generating fresh start prompts: {exc}. Skipping fresh start prompts.",
        )
        return []
    except Exception as exc:
        init_pop_report.failed_fresh_prompts(
            num_fresh_starts,
            f"Error generating fresh start prompts: {exc}. Skipping fresh start prompts.",
        )
        return []


def _generate_variation_prompts(
    *,
    init_pop_report: Any,
    prompt: chat_prompt.ChatPrompt,
    prompts: PromptLibrary,
    task_desc_for_llm: str,
    output_style_guidance: str,
    model: str,
    model_parameters: dict[str, Any],
    num_variations_on_initial: int,
    optimizer: Any | None = None,
) -> list[chat_prompt.ChatPrompt]:
    if num_variations_on_initial <= 0:
        return []

    optimize_tools = bool(getattr(optimizer, "_optimize_tools", False))
    tool_names = getattr(optimizer, "_tool_names", None)
    metric = getattr(optimizer, "_evaluation_metric", None)

    init_pop_report.start_variations(num_variations_on_initial)
    user_prompt_for_variation = prompts.get(
        "variation_user_prompt_template",
        initial_prompt_messages=prompt.get_messages(),
        task_description=task_desc_for_llm,
        style=output_style_guidance,
        num_variations=num_variations_on_initial,
    )
    try:
        response_items = _call_and_normalize_model_response(
            messages=[
                {
                    "role": "system",
                    "content": prompts.get(
                        "variation_system_prompt_template",
                        style=output_style_guidance,
                    ),
                },
                {"role": "user", "content": user_prompt_for_variation},
            ],
            model=model,
            model_parameters=model_parameters,
            debug_tag="population_variation_response",
        )
        generated_prompts_variations: list[list[dict[str, Any]]] = []
        for response_item in response_items:
            json_response_variations = json.loads(normalize_llm_text(response_item))
            generated_prompts_variations.extend(
                [
                    p["prompt"]
                    for p in json_response_variations.get("prompts", [])
                    if isinstance(p, dict) and "prompt" in p
                ]
            )

        if not generated_prompts_variations:
            init_pop_report.failed_variations(
                num_variations_on_initial,
                "Could not parse 'prompts' list for variations. Skipping variations.",
            )
            return []

        init_pop_report.success_variations(len(generated_prompts_variations))
        for idx, prompt_messages in enumerate(generated_prompts_variations):
            debug_log(
                "population_variation_prompt",
                index=idx,
                prompt=compact_debug_text(json.dumps(prompt_messages)),
            )
        return _messages_to_chat_prompts(
            generated_prompts_variations,
            prompt,
            optimize_tools=optimize_tools,
            tool_names=tool_names,
            metric=metric,
            optimizer=optimizer,
        )
    except Exception as exc:
        init_pop_report.failed_variations(
            num_variations_on_initial,
            f"Error calling LLM for initial population variations: {exc}",
        )
        return []


def _messages_to_chat_prompts(
    messages_list: list[list[dict[str, Any]]],
    base_prompt: chat_prompt.ChatPrompt,
    optimize_tools: bool,
    tool_names: list[str] | None,
    metric: Any,
    optimizer: Any | None = None,
) -> list[chat_prompt.ChatPrompt]:
    prompts: list[chat_prompt.ChatPrompt] = [
        chat_prompt.ChatPrompt(
            messages=messages,
            tools=base_prompt.tools,
            function_map=base_prompt.function_map,
            model=base_prompt.model,
            model_parameters=base_prompt.model_kwargs,
        )
        for messages in messages_list
    ]
    # If optimizing tools apply tools to chat prompt
    if optimize_tools:
        updated: list[chat_prompt.ChatPrompt] = []
        for prompt in prompts:
            if not prompt.tools:
                updated.append(prompt)
                continue
            updated.append(
                tool_ops.apply_tool_description_update(
                    optimizer=optimizer,
                    prompt=prompt,
                    tool_names=tool_names,
                    round_num=0,
                    metric=metric,
                )
            )
        return updated
    return prompts


def _dedupe_population(
    population: list[chat_prompt.ChatPrompt],
) -> list[chat_prompt.ChatPrompt]:
    final_population_set: set[str] = set()
    final_population_list: list[chat_prompt.ChatPrompt] = []
    for prompt in population:
        serialized = json.dumps(prompt.get_messages())
        if serialized not in final_population_set:
            final_population_set.add(serialized)
            final_population_list.append(prompt)
    return final_population_list


def _call_and_normalize_model_response(
    *,
    messages: list[dict[str, Any]],
    model: str,
    model_parameters: dict[str, Any],
    debug_tag: str,
) -> list[Any]:
    response_content = _llm_calls.call_model(
        messages=messages,
        model=model,
        model_parameters=model_parameters,
        is_reasoning=True,
        return_all=_llm_calls.requested_multiple_candidates(model_parameters),
    )
    response_items = (
        response_content if isinstance(response_content, list) else [response_content]
    )
    response_items = [
        normalize_llm_text(item) if isinstance(item, str) else item
        for item in response_items
    ]
    cleaned = [
        compact_debug_text(item) if isinstance(item, str) else item
        for item in response_items
    ]
    debug_log(debug_tag, prompts=cleaned)
    return response_items


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


def maybe_restart_population(
    *,
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    population: list[Any],
    hof: tools.HallOfFame,
    best_prompts_so_far: dict[str, chat_prompt.ChatPrompt],
    on_restart: Callable[[int], None] | None = None,
) -> list[Any]:
    should_restart, gens_since_pop_improvement, best_primary_score_history = (
        should_restart_population(
            curr_best=optimizer._population_best_score(population),
            best_primary_score_history=optimizer._best_primary_score_history,
            gens_since_pop_improvement=optimizer._gens_since_pop_improvement,
            default_restart_threshold=optimizer.DEFAULT_RESTART_THRESHOLD,
            default_restart_generations=optimizer.DEFAULT_RESTART_GENERATIONS,
        )
    )
    optimizer._gens_since_pop_improvement = gens_since_pop_improvement
    optimizer._best_primary_score_history = best_primary_score_history

    if not should_restart:
        return population

    if on_restart is not None:
        on_restart(optimizer.DEFAULT_RESTART_GENERATIONS)
    return restart_population(
        optimizer=optimizer,
        hof=hof,
        population=population,
        best_prompts_so_far=best_prompts_so_far,
    )


def post_population_candidates(
    *,
    optimizer: "evolutionary_optimizer.EvolutionaryOptimizer",
    context: Any,
    population: list[Any],
    generation_idx: int,
    round_handle: Any,
    use_valid_index: bool,
) -> None:
    """Record and post candidate entries for a population."""
    valid_idx = 0
    for idx, ind in enumerate(population):
        if not ind.fitness.valid:
            continue
        primary_score = ind.fitness.values[0]
        if primary_score is None or not math.isfinite(primary_score):
            continue
        candidate_prompts = optimizer._individual_to_prompts(ind)
        metrics: dict[str, Any] | None = None
        if optimizer.enable_moo and len(ind.fitness.values) > 1:
            metrics = {
                "primary": ind.fitness.values[0],
                "length": ind.fitness.values[1],
            }
        runtime.record_and_post_trial(
            optimizer=optimizer,
            context=context,
            prompt_or_payload=candidate_prompts,
            score=primary_score,
            candidate_id=f"gen{generation_idx}_ind{valid_idx if use_valid_index else idx}",
            metrics=metrics,
            round_handle=round_handle,
        )
        valid_idx += 1
