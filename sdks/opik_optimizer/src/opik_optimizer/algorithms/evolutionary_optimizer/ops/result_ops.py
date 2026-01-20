from __future__ import annotations

from typing import Any, TYPE_CHECKING
import json
import logging

try:
    from deap import tools

    _DEAP_IMPORT_ERROR: Exception | None = None
except Exception as exc:  # pragma: no cover - exercised when DEAP is missing
    tools = None
    _DEAP_IMPORT_ERROR = exc

from ....core import runtime
from . import pareto_ops
from ....core.state import AlgorithmResult, OptimizationContext
from ....utils.logging import debug_log

if TYPE_CHECKING:  # pragma: no cover
    from ...evolutionary_optimizer import EvolutionaryOptimizer

logger = logging.getLogger(__name__)


def _require_deap() -> None:
    if tools is None:
        raise RuntimeError(
            "DEAP is required for EvolutionaryOptimizer. "
            "Install the optimizer extras that include DEAP."
        ) from _DEAP_IMPORT_ERROR


def build_algorithm_result(
    optimizer: EvolutionaryOptimizer,
    *,
    context: OptimizationContext,
    optimizable_prompts: dict[str, Any],
    best_prompts_overall: dict[str, Any],
    best_primary_score_overall: float,
    initial_primary_score: float,
    initial_length: float,
    hof: tools.HallOfFame | tools.ParetoFront,
    effective_output_style_guidance: str,
    generation_idx: int,
) -> AlgorithmResult:
    _require_deap()
    metric = context.metric
    final_details: dict[str, Any] = {}

    if optimizer.enable_moo:
        final_results_log = pareto_ops.format_pareto_log(hof)
        if hof and len(hof) > 0:
            best_overall_solution = pareto_ops.choose_best_from_front(
                hof=hof, enable_moo=True
            )
            if best_overall_solution is None:
                best_overall_solution = sorted(
                    hof, key=lambda ind: ind.fitness.values[0], reverse=True
                )[0]
            final_best_prompts = optimizer._individual_to_prompts(best_overall_solution)
            final_primary_score = best_overall_solution.fitness.values[0]
            final_length = best_overall_solution.fitness.values[1]
            logger.info(final_results_log)
            logger.info(
                "Best prompts (highest primary score from Pareto front): %d prompts",
                len(final_best_prompts),
            )
            logger.info(
                "  Primary Score (%s): %.4f", metric.__name__, final_primary_score
            )
            logger.info("  Length: %.0f", final_length)
            final_details.update(
                {
                    "initial_primary_score": initial_primary_score,
                    "initial_length": initial_length,
                    "final_prompts": final_best_prompts,
                    "final_primary_score_representative": final_primary_score,
                    "final_length_representative": final_length,
                    "pareto_front_solutions": pareto_ops.serialize_pareto_solutions(
                        hof
                    ),
                }
            )
        else:
            logger.warning("MOO: ParetoFront is empty. Reporting last known best.")
            final_best_prompts = best_prompts_overall
            final_primary_score = best_primary_score_overall
            all_messages = {
                name: p.get_messages() for name, p in final_best_prompts.items()
            }
            final_length = float(len(json.dumps(all_messages)))
            final_details.update(
                {
                    "initial_primary_score": initial_primary_score,
                    "initial_length": initial_length,
                    "final_prompts": final_best_prompts,
                    "final_primary_score_representative": final_primary_score,
                    "final_length_representative": final_length,
                    "pareto_front_solutions": pareto_ops.serialize_pareto_solutions(
                        hof
                    ),
                }
            )
    else:
        final_best_prompts = best_prompts_overall
        final_primary_score = best_primary_score_overall
        logger.info(
            "Final best prompts from Hall of Fame: %d prompts",
            len(final_best_prompts),
        )
        logger.info("Final best score (%s): %.4f", metric.__name__, final_primary_score)
        initial_messages = {
            name: p.get_messages() for name, p in optimizable_prompts.items()
        }
        final_details.update(
            {
                "initial_prompts": initial_messages,
                "initial_score": initial_primary_score,
                "initial_score_for_display": initial_primary_score,
                "final_prompts": final_best_prompts,
                "final_score": final_primary_score,
            }
        )

    logger.info("Total LLM calls during optimization: %d", optimizer.llm_call_counter)
    logger.info("Total prompt evaluations: %d", context.trials_completed)

    all_final_tools: dict[str, Any] = {}
    for name, p in final_best_prompts.items():
        if p.tools:
            all_final_tools[name] = p.tools

    metadata: dict[str, Any] = {
        "total_generations_run": generation_idx,
        "elitism_size": (
            optimizer.elitism_size
            if not optimizer.enable_moo
            else "N/A (MOO uses NSGA-II)"
        ),
        "adaptive_mutation": optimizer.adaptive_mutation,
        "llm_crossover_enabled": optimizer.enable_llm_crossover,
        "seed": optimizer.seed,
        "user_output_style_guidance": optimizer.output_style_guidance,
        "infer_output_style": optimizer.infer_output_style,
        "final_effective_output_style_guidance": effective_output_style_guidance,
    }
    metadata.update(final_details)
    if all_final_tools:
        metadata["final_tools"] = all_final_tools

    history_entries = optimizer.get_history_entries()
    if not history_entries:
        fallback_round = optimizer.pre_round(context)
        runtime.record_and_post_trial(
            optimizer=optimizer,
            context=context,
            prompt_or_payload=final_best_prompts,
            score=final_primary_score,
            candidate_id="final_best",
            round_handle=fallback_round,
        )
        optimizer.post_round(
            fallback_round,
            context=context,
            best_score=final_primary_score,
            best_prompt=final_best_prompts,
            stop_reason=context.finish_reason,
        )
        history_entries = optimizer.get_history_entries()

    debug_log(
        "evolutionary_result",
        generations=generation_idx,
        best_score=final_primary_score,
    )

    return AlgorithmResult(
        best_prompts=final_best_prompts,
        best_score=final_primary_score,
        history=history_entries,
        metadata=metadata,
    )
