from __future__ import annotations

import copy
from typing import Any, TYPE_CHECKING, cast
from collections.abc import Callable
import hashlib
import json
import logging

try:
    from deap import tools

    _DEAP_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - exercised when DEAP is missing
    tools = None
    _DEAP_IMPORT_ERROR = exc

from ....core.state import OptimizationContext
from ....utils import display as display_utils
from ....utils import rng as rng_utils
from .. import reporting
from . import crossover_ops, mutation_ops, pareto_ops, population_ops, style_ops

if TYPE_CHECKING:  # pragma: no cover
    from ...evolutionary_optimizer import EvolutionaryOptimizer

logger = logging.getLogger(__name__)


def _require_deap() -> None:
    if tools is None:
        raise RuntimeError(
            "DEAP is required for EvolutionaryOptimizer. "
            "Install the optimizer extras that include DEAP."
        ) from _DEAP_IMPORT_ERROR


def build_deap_evaluator(
    optimizer: EvolutionaryOptimizer,
    context: OptimizationContext,
    experiment_config: dict[str, Any] | None,
) -> Callable[[Any], tuple[float, ...]]:
    def _build_sampling_tag(individual: Any) -> str:
        generation_idx = getattr(optimizer, "_current_generation_idx", None)
        try:
            payload = json.dumps(dict(individual), sort_keys=True, default=str)
        except TypeError:
            payload = json.dumps(str(individual))
        candidate_hash = hashlib.sha256(payload.encode("utf-8")).hexdigest()[:8]
        return optimizer._build_sampling_tag(
            scope="evolutionary",
            round_index=generation_idx,
            candidate_id=candidate_hash,
        )

    if optimizer.enable_moo:

        def _evaluate(individual: Any) -> tuple[float, ...]:
            if optimizer._should_stop_context(context):
                return (-float("inf"), float("inf"))
            prompts_bundle = optimizer._individual_to_prompts(individual)
            sampling_tag = _build_sampling_tag(individual)
            primary_score = optimizer.evaluate(
                context,
                prompts_bundle,
                experiment_config=(experiment_config or {}).copy(),
                sampling_tag=sampling_tag,
            )
            prompt_length = float(len(str(json.dumps(dict(individual)))))
            return (primary_score, prompt_length)

    else:

        def _evaluate(individual: Any) -> tuple[float, ...]:
            if optimizer._should_stop_context(context):
                return (-float("inf"),)
            prompts_bundle = optimizer._individual_to_prompts(individual)
            sampling_tag = _build_sampling_tag(individual)
            fitness_score = optimizer.evaluate(
                context,
                prompts_bundle,
                experiment_config=(experiment_config or {}).copy(),
                sampling_tag=sampling_tag,
            )
            return (fitness_score,)

    optimizer._deap_evaluate_individual_fitness = _evaluate
    return _evaluate


def resolve_output_style_guidance(
    optimizer: EvolutionaryOptimizer,
    *,
    dataset: Any,
) -> str:
    effective = optimizer.output_style_guidance
    if optimizer.infer_output_style and (
        optimizer.output_style_guidance is None
        or optimizer.output_style_guidance == optimizer.DEFAULT_OUTPUT_STYLE_GUIDANCE
    ):
        inferred_style = style_ops.infer_output_style_from_dataset(
            dataset=dataset,
            model=optimizer.model,
            model_parameters=optimizer.model_parameters,
            verbose=optimizer.verbose,
            prompts=optimizer._prompts,
        )
        if inferred_style:
            effective = inferred_style
            optimizer.output_style_guidance = inferred_style
        else:
            logger.warning(
                "Failed to infer output style, using default or user-provided guidance."
            )

    if optimizer.output_style_guidance is None:
        optimizer.output_style_guidance = optimizer.DEFAULT_OUTPUT_STYLE_GUIDANCE
    return effective or optimizer.DEFAULT_OUTPUT_STYLE_GUIDANCE


def initialize_population(
    optimizer: EvolutionaryOptimizer,
    *,
    optimizable_prompts: dict[str, Any],
    output_style_guidance: str,
) -> list[Any]:
    prompt_variations: dict[str, list[Any]] = {}
    for prompt_name, prompt_obj in optimizable_prompts.items():
        variations = population_ops.initialize_population(
            prompt=prompt_obj,
            output_style_guidance=output_style_guidance,
            model=optimizer.model,
            model_parameters=optimizer.model_parameters,
            optimization_id=optimizer.current_optimization_id,
            population_size=optimizer.population_size,
            verbose=optimizer.verbose,
            prompts=optimizer._prompts,
            optimizer=optimizer,
        )
        prompt_variations[prompt_name] = variations

    deap_population = []
    for i in range(optimizer.population_size):
        prompts_for_individual = {
            name: variations[i % len(variations)]
            for name, variations in prompt_variations.items()
        }
        deap_population.append(
            optimizer._create_individual_from_prompts(prompts_for_individual)
        )
    return deap_population[: optimizer.population_size]


def evaluate_initial_population(
    optimizer: EvolutionaryOptimizer,
    *,
    context: OptimizationContext,
    deap_population: list[Any],
    hof: tools.HallOfFame | tools.ParetoFront,
    best_primary_score_overall: float,
    best_prompts_overall: dict[str, Any],
) -> tuple[float, dict[str, Any]]:
    _require_deap()
    optimizer._current_generation_idx = 0
    logger.debug("Evaluating initial population")
    fitnesses: list[Any] = list(
        map(optimizer._deap_evaluate_individual_fitness, deap_population)
    )
    _best_score = max(best_primary_score_overall, max([x[0] for x in fitnesses]))

    for ind, fit in zip(deap_population, fitnesses):
        if optimizer.enable_moo:
            ind.fitness.values = fit
        else:
            ind.fitness.values = tuple([fit[0]])
    logger.debug(f"Initial population evaluated, best_score={_best_score:.4f}")

    hof.update(deap_population)

    if hof and len(hof) > 0:
        current_best = pareto_ops.choose_best_from_front(
            hof=hof, enable_moo=optimizer.enable_moo
        )
        if current_best is not None:
            best_primary_score_overall = current_best.fitness.values[0]
            best_prompts_overall = optimizer._individual_to_prompts(current_best)

        if optimizer.enable_moo:
            logger.info(
                f"Gen {0}: New best primary score: {best_primary_score_overall:.4f}, Prompts: {len(best_prompts_overall)}"
            )
        else:
            logger.info(f"Gen {0}: New best score: {best_primary_score_overall:.4f}")

    return best_primary_score_overall, best_prompts_overall


def post_population_round(
    optimizer: EvolutionaryOptimizer,
    *,
    context: OptimizationContext,
    population: list[Any],
    hof: tools.HallOfFame | tools.ParetoFront,
    generation_idx: int,
    round_handle: Any,
    initial_primary_score: float,
    best_primary_score_overall: float,
    best_prompts_overall: dict[str, Any],
    use_valid_index: bool,
) -> None:
    _require_deap()
    population_ops.post_population_candidates(
        optimizer=optimizer,
        context=context,
        population=population,
        generation_idx=generation_idx,
        round_handle=round_handle,
        use_valid_index=use_valid_index,
    )
    pareto_front = None
    selection_meta = None
    if optimizer.enable_moo:
        pareto_front = pareto_ops.build_pareto_front(hof, generation_idx)
        selection_meta = pareto_ops.selection_meta(
            selection_policy=getattr(optimizer, "selection_policy", "tournament"),
            pareto_front=pareto_front,
        )
        optimizer.set_pareto_front(pareto_front)
        optimizer.set_selection_meta(selection_meta)

    optimizer.post_round(
        round_handle,
        context=context,
        best_score=best_primary_score_overall,
        best_prompt=best_prompts_overall,
        dataset_split=context.dataset_split
        if hasattr(context, "dataset_split")
        else None,
        stop_reason=context.finish_reason,
        extras={
            "stopped": context.should_stop,
            "stop_reason": context.finish_reason,
            "improvement": _calculate_improvement(
                best_primary_score_overall, initial_primary_score
            ),
        },
    )


def run_generation(
    optimizer: EvolutionaryOptimizer,
    *,
    generation_idx: int,
    population: list[Any],
    initial_prompts: dict[str, Any],
    hof: tools.HallOfFame,
    best_primary_score_overall: float,
) -> tuple[list[Any], int]:
    _require_deap()
    optimizer._current_generation_idx = generation_idx
    best_gen_score = 0.0
    gen_rng = optimizer._derive_rng("generation", generation_idx)
    crossover_rng = rng_utils.derive_rng(gen_rng, "crossover")
    mutation_rng = rng_utils.derive_rng(gen_rng, "mutation")

    offspring = pareto_ops.select_population(
        population=population,
        population_size=optimizer.population_size,
        elitism_size=optimizer.elitism_size,
        tournament_size=optimizer.tournament_size,
        enable_moo=optimizer.enable_moo,
    )

    if optimizer._reporter:
        optimizer._reporter.performing_crossover()
    offspring = [copy.deepcopy(ind) for ind in offspring]
    for i in range(0, len(offspring), 2):
        if i + 1 < len(offspring):
            c1, c2 = offspring[i], offspring[i + 1]
            if gen_rng.random() < optimizer.crossover_rate:
                if optimizer.enable_llm_crossover:
                    c1_new, c2_new = crossover_ops.llm_deap_crossover(
                        c1,
                        c2,
                        output_style_guidance=optimizer.output_style_guidance,
                        model=optimizer.model,
                        model_parameters=optimizer.model_parameters,
                        use_semantic=optimizer.enable_semantic_crossover,
                        verbose=optimizer.verbose,
                        prompts=optimizer._prompts,
                        rng=crossover_rng,
                        optimizer=optimizer,
                    )
                else:
                    c1_new, c2_new = crossover_ops.deap_crossover(
                        c1,
                        c2,
                        optimizer=optimizer,
                        verbose=optimizer.verbose,
                        rng=crossover_rng,
                    )
                offspring[i], offspring[i + 1] = c1_new, c2_new
                del offspring[i].fitness.values, offspring[i + 1].fitness.values

    if optimizer._reporter:
        optimizer._reporter.performing_mutation()
    optimizer._current_population = list(offspring)
    if hasattr(optimizer, "_best_fitness_history"):
        valid_scores = [ind.fitness.values[0] for ind in offspring if ind.fitness.valid]
        if valid_scores:
            optimizer._best_fitness_history.append(max(valid_scores))
    mut_rate = optimizer._get_adaptive_mutation_rate()
    for i, ind in enumerate(offspring):
        if gen_rng.random() < mut_rate:
            new_ind = mutation_ops.deap_mutation(
                individual=ind,
                optimizer=optimizer,
                current_population=optimizer._current_population,
                output_style_guidance=optimizer.output_style_guidance,
                initial_prompts=initial_prompts,
                model=optimizer.model,
                model_parameters=optimizer.model_parameters,
                diversity_threshold=optimizer.DEFAULT_DIVERSITY_THRESHOLD,
                optimization_id=optimizer.current_optimization_id,
                verbose=optimizer.verbose,
                prompts=optimizer._prompts,
                allowed_roles=getattr(optimizer, "_optimizable_roles", None),
                rng=mutation_rng,
            )
            offspring[i] = new_ind
            del offspring[i].fitness.values

    invalid = [ind for ind in offspring if not ind.fitness.valid]
    if optimizer._reporter:
        optimizer._reporter.performing_evaluation(len(invalid))
    for ind in invalid:
        fit = optimizer._deap_evaluate_individual_fitness(ind)
        if optimizer.enable_moo:
            ind.fitness.values = fit
        else:
            ind.fitness.values = tuple([fit[0]])
        best_gen_score = max(best_gen_score, fit[0])

    hof.update(offspring)
    logger.debug(
        "Generation %d: best_score=%.4f, overall_best=%.4f",
        generation_idx,
        best_gen_score,
        best_primary_score_overall,
    )

    return offspring, len(invalid)


def run_generations(
    optimizer: EvolutionaryOptimizer,
    *,
    context: OptimizationContext,
    deap_population: list[Any],
    hof: tools.HallOfFame | tools.ParetoFront,
    optimizable_prompts: dict[str, Any],
    best_primary_score_overall: float,
    best_prompts_overall: dict[str, Any],
    initial_primary_score: float,
    max_trials: int,
) -> tuple[list[Any], float, dict[str, Any], int]:
    _require_deap()
    generation_idx = 0
    with reporting.start_evolutionary_algo(verbose=optimizer.verbose) as evo_reporter:
        optimizer._set_reporter(evo_reporter)
        try:
            for generation_idx in range(1, optimizer.num_generations + 1):
                optimizer._current_round = generation_idx - 1
                if optimizer._should_stop_context(context):
                    if context.finish_reason == "max_trials":
                        logger.info(
                            "Stopping optimization: max_trials (%d) reached after %d generations",
                            max_trials,
                            generation_idx - 1,
                        )
                    break

                reporting.log_round_start(
                    round_index=generation_idx,
                    population=optimizer.population_size,
                    trials_completed=context.trials_completed,
                )
                evo_reporter.start_gen(generation_idx, optimizer.num_generations)
                round_handle = optimizer.pre_round(context)

                deap_population = population_ops.maybe_restart_population(
                    optimizer=optimizer,
                    population=deap_population,
                    hof=cast(tools.HallOfFame, hof),
                    best_prompts_so_far=best_prompts_overall,
                    on_restart=evo_reporter.restart_population,
                )

                deap_population, _ = optimizer._run_generation(
                    generation_idx,
                    deap_population,
                    optimizable_prompts,
                    hof,
                    best_primary_score_overall,
                )

                if hof:
                    current_best_ind = pareto_ops.choose_best_from_front(
                        hof=hof, enable_moo=optimizer.enable_moo
                    )
                    if current_best_ind is not None:
                        updated_best_primary_score = current_best_ind.fitness.values[0]
                        if updated_best_primary_score > best_primary_score_overall:
                            best_primary_score_overall = updated_best_primary_score
                            best_prompts_overall = optimizer._individual_to_prompts(
                                current_best_ind
                            )
                            optimizer._generations_without_overall_improvement = 0
                        else:
                            optimizer._generations_without_overall_improvement += 1
                else:
                    optimizer._generations_without_overall_improvement += 1

                if (
                    optimizer._generations_without_overall_improvement
                    >= optimizer.DEFAULT_EARLY_STOPPING_GENERATIONS
                ):
                    logger.info(
                        "No overall improvement for %d generations – early stopping at gen %d.",
                        optimizer.DEFAULT_EARLY_STOPPING_GENERATIONS,
                        generation_idx,
                    )
                    break

                reporting.end_gen(
                    generation_idx,
                    best_primary_score_overall,
                    initial_primary_score,
                    verbose=optimizer.verbose,
                )
                reporting.log_round_end(
                    round_index=generation_idx,
                    best_score=best_primary_score_overall,
                    trials_completed=context.trials_completed,
                )

                population_ops.post_population_candidates(
                    optimizer=optimizer,
                    context=context,
                    population=deap_population,
                    generation_idx=generation_idx,
                    round_handle=round_handle,
                    use_valid_index=True,
                )

                pareto_front = None
                selection_meta = None
                if optimizer.enable_moo:
                    pareto_front = pareto_ops.build_pareto_front(hof, generation_idx)
                    selection_meta = pareto_ops.selection_meta(
                        selection_policy=getattr(
                            optimizer, "selection_policy", "tournament"
                        ),
                        pareto_front=pareto_front,
                    )
                optimizer.post_round(
                    round_handle=round_handle,
                    context=context,
                    best_score=best_primary_score_overall,
                    best_candidate=best_prompts_overall,
                    stop_reason=context.finish_reason,
                    pareto_front=pareto_front,
                    selection_meta=selection_meta,
                    extras={
                        "improvement": _calculate_improvement(
                            best_primary_score_overall, initial_primary_score
                        ),
                    },
                )
        finally:
            if context.finish_reason == "max_trials":
                display_utils.display_message(
                    f"Stopped early: max_trials reached after round {optimizer._current_round + 1}",
                    verbose=optimizer.verbose,
                )
            optimizer._clear_reporter()

    return (
        deap_population,
        best_primary_score_overall,
        best_prompts_overall,
        generation_idx,
    )


def _calculate_improvement(best_score: float, initial_score: float) -> float:
    if initial_score and initial_score != 0:
        return (best_score - initial_score) / abs(initial_score)
    return 1.0 if best_score > 0 else 0.0
