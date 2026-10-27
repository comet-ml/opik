from ....utils.prompt_editing import describe_text_segments
from ....utils.prompt_editing import apply_text_edits, TextEdit
from ..types import PopulationResponse
from ....core.reasoning import ReasoningEvidence
from typing import Any
from collections.abc import Callable
import math

import json
import logging

from deap import tools

from .. import reporting, helpers, evolutionary_optimizer  # noqa: F401
from ....api_objects import chat_prompt
from ....core import runtime
from ....core import llm_calls as _llm_calls
from ....utils.prompt_library import PromptLibrary
from ....utils.logging import compact_debug_text, debug_log
from . import tool_ops


logger = logging.getLogger(__name__)


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
            optimizer=optimizer,
            prompt_key=prompt_name,
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
    prompt_key: str | None = None,
) -> list[chat_prompt.ChatPrompt]:
    """Initialize the population with diverse variations of the initial prompt,
    including some 'fresh start' prompts based purely on task description.
    All generated prompts should aim to elicit answers matching self.output_style_guidance.
    ``prompt_key`` is the prompt's key in the bundle (defaults to ``prompt.name``).
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
                prompt_key=prompt_key or prompt.name,
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
                prompt_key=prompt_key or prompt.name,
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
    prompt_key: str,
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
            evidence=ReasoningEvidence(prompts={prompt.name: prompt}),
        )

        parsed_prompts = [
            candidate.edits
            for response in response_items
            for candidate in response.prompts
        ]

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
                prompt=compact_debug_text(str(prompt_messages)),
            )
        init_pop_report.success_fresh_prompts(len(prompts_to_use))
        return _edits_to_chat_prompts(
            prompts_to_use,
            prompt,
            prompt_key=prompt_key,
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
    prompt_key: str,
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
        initial_prompt_messages=describe_text_segments(prompt),
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
            evidence=ReasoningEvidence(prompts={prompt.name: prompt}),
        )
        generated_prompts_variations = [
            candidate.edits
            for response in response_items
            for candidate in response.prompts
        ]

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
                prompt=compact_debug_text(str(prompt_messages)),
            )
        return _edits_to_chat_prompts(
            generated_prompts_variations,
            prompt,
            prompt_key=prompt_key,
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


def _edits_to_chat_prompts(
    edit_sets: list[list[TextEdit]],
    base_prompt: chat_prompt.ChatPrompt,
    prompt_key: str,
    optimize_tools: bool,
    tool_names: list[str] | None,
    metric: Any,
    optimizer: Any | None = None,
) -> list[chat_prompt.ChatPrompt]:
    prompts: list[chat_prompt.ChatPrompt] = [
        apply_text_edits(
            base_prompt, edits, getattr(optimizer, "_optimizable_roles", None)
        )
        for edits in edit_sets
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
                    prompt_key=prompt_key,
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
    evidence: ReasoningEvidence | None = None,
) -> list[PopulationResponse]:
    response_content = _llm_calls.call_model(
        evidence=evidence,
        response_model=PopulationResponse,
        messages=messages,
        model=model,
        model_parameters=model_parameters,
        is_reasoning=True,
        return_all=_llm_calls.requested_multiple_candidates(model_parameters),
    )
    response_items = (
        response_content if isinstance(response_content, list) else [response_content]
    )
    debug_log(debug_tag, prompts=[response.model_dump() for response in response_items])
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
