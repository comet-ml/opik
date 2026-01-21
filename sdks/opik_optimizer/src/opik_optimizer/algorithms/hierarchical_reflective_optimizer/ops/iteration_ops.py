from __future__ import annotations

import logging
from contextlib import nullcontext
from typing import Any, TYPE_CHECKING

import opik

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....constants import normalize_eval_threads
from ....core import runtime
from ....core.state import OptimizationContext
from ....utils.logging import debug_log
from ....utils.reporting import convert_tqdm_to_rich
from .. import helpers, reporting

if TYPE_CHECKING:  # pragma: no cover
    from ..hierarchical_reflective_optimizer import HierarchicalReflectiveOptimizer
from ..types import HierarchicalRootCauseAnalysis

logger = logging.getLogger(__name__)


def run_root_cause_analysis(
    *,
    optimizer: HierarchicalReflectiveOptimizer,
    context: OptimizationContext,
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    dataset: opik.Dataset,
    metric: MetricFunction,
    agent: Any,
    n_samples: int | None,
    sampling_tag: str | None = None,
) -> HierarchicalRootCauseAnalysis:
    with reporting.display_root_cause_analysis(
        verbose=optimizer.verbose
    ) as rca_reporter:
        optimizer._set_reporter(rca_reporter)
        try:
            optimizer.pre_trial(context, best_prompts)
            suppress_progress = bool(
                (context.extra_params or {}).get("suppress_evaluation_progress", False)
            )
            progress_ctx = (
                nullcontext()
                if suppress_progress
                else convert_tqdm_to_rich("  Evaluation", verbose=optimizer.verbose)
            )
            with progress_ctx:
                train_dataset_experiment_result = optimizer.evaluate_prompt(
                    prompt=best_prompts,
                    dataset=dataset,
                    metric=metric,
                    agent=agent,
                    n_samples=n_samples,
                    n_samples_strategy=context.n_samples_strategy,
                    n_threads=normalize_eval_threads(
                        getattr(optimizer, "n_threads", None)
                    ),
                    experiment_config=context.experiment_config,
                    return_evaluation_result=True,
                    allow_tool_use=context.allow_tool_use,
                    sampling_tag=sampling_tag,
                )
            hierarchical_analysis = optimizer._hierarchical_root_cause_analysis(
                train_dataset_experiment_result
            )
            rca_reporter.set_completed(
                hierarchical_analysis.total_test_cases,
                hierarchical_analysis.num_batches,
            )
        finally:
            optimizer._clear_reporter()

    return hierarchical_analysis


def improve_over_failure_modes(
    *,
    optimizer: HierarchicalReflectiveOptimizer,
    context: OptimizationContext,
    hierarchical_analysis: HierarchicalRootCauseAnalysis,
    optimizable_prompts: dict[str, chat_prompt.ChatPrompt],
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    dataset: opik.Dataset,
    validation_dataset: opik.Dataset | None,
    metric: MetricFunction,
    agent: Any,
    n_samples: int | None,
    max_retries: int,
    max_trials: int,
    optimization_id: str | None,
    round_handle: Any,
) -> tuple[dict[str, chat_prompt.ChatPrompt], float]:
    for root_cause in hierarchical_analysis.unified_failure_modes:
        with reporting.display_prompt_improvement(
            root_cause.name, verbose=optimizer.verbose
        ):
            max_attempts = max_retries + 1
            improved_chat_prompts = None
            improved_score = None

            for attempt in range(1, max_attempts + 1):
                if optimizer._should_stop_context(context):
                    logger.info(
                        "Reached max_trials limit (%s) during failure mode '%s'. Stopping optimization.",
                        max_trials,
                        root_cause.name,
                    )
                    optimizer._should_stop_optimization = True
                    break

                trials_before_attempt = context.trials_completed
                (
                    improved_chat_prompts,
                    improved_score,
                    _,
                ) = optimizer._generate_and_evaluate_improvement(
                    root_cause=root_cause,
                    best_prompts=best_prompts,
                    best_score=best_score,
                    original_prompts=optimizable_prompts,
                    dataset=dataset,
                    validation_dataset=validation_dataset,
                    metric=metric,
                    agent=agent,
                    optimization_id=optimization_id,
                    n_samples=n_samples,
                    attempt=attempt,
                    max_attempts=max_attempts,
                    context=context,
                    round_handle=round_handle,
                )
                if context.trials_completed == trials_before_attempt:
                    score_for_accounting = (
                        improved_score if improved_score is not None else best_score
                    )
                    optimizer._record_trial_score(
                        context,
                        score_for_accounting,
                        improved_chat_prompts or best_prompts,
                    )

                if (
                    optimizer.skip_perfect_score
                    and improved_score is not None
                    and improved_score >= optimizer.perfect_score
                ):
                    context.should_stop = True
                    context.finish_reason = "perfect_score"
                    break

                if improved_score is not None and improved_score > best_score:
                    reporting.display_iteration_improvement(
                        improvement=helpers.calculate_improvement(
                            float(improved_score), float(best_score)
                        ),
                        current_score=float(improved_score),
                        best_score=float(best_score),
                        verbose=optimizer.verbose,
                    )
                    break

                if attempt < max_attempts and context.trials_completed < max_trials:
                    reporting.display_retry_attempt(
                        attempt=attempt,
                        max_attempts=max_attempts,
                        failure_mode_name=root_cause.name,
                        verbose=optimizer.verbose,
                    )

        if optimizer._should_stop_optimization or context.should_stop:
            break

        if (
            improved_score is not None
            and improved_chat_prompts is not None
            and improved_score > best_score
        ):
            improvement = helpers.calculate_improvement(improved_score, best_score)
            logger.info(
                "Improvement %.2f%%: %.4f -> %.4f",
                improvement * 100,
                best_score,
                improved_score,
            )
            best_score = improved_score
            best_prompts = improved_chat_prompts
            logger.info("Updated best prompt after addressing '%s'", root_cause.name)
        else:
            logger.debug(
                "Keeping previous best prompt, no improvement from '%s'",
                root_cause.name,
            )

    return best_prompts, best_score


def finalize_iteration(
    *,
    optimizer: HierarchicalReflectiveOptimizer,
    context: OptimizationContext,
    round_index: int,
    hierarchical_analysis: HierarchicalRootCauseAnalysis,
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    best_score: float,
    previous_iteration_score: float,
    round_handle: Any,
) -> float:
    iteration_improvement = helpers.calculate_improvement(
        best_score, previous_iteration_score
    )
    logger.info(
        "Round %s complete. Score: %.4f, Improvement: %.2f%%",
        round_index,
        best_score,
        iteration_improvement * 100,
    )

    runtime.record_and_post_trial(
        optimizer=optimizer,
        context=context,
        prompt_or_payload=best_prompts,
        score=best_score,
        round_handle=round_handle,
        post_metrics=None,
        post_extras={
            "failure_modes": [
                fm.name for fm in hierarchical_analysis.unified_failure_modes
            ],
            "trials_completed": context.trials_completed,
        },
    )
    optimizer.post_round(
        round_handle=round_handle,
        context=context,
        best_score=best_score,
        best_candidate=best_prompts,
        stop_reason=context.finish_reason if context.should_stop else None,
        extras={"improvement": iteration_improvement},
    )
    debug_log(
        "round_end",
        round_index=round_index,
        best_score=best_score,
        trials_completed=context.trials_completed,
    )

    if abs(iteration_improvement) < optimizer.convergence_threshold:
        logger.info(
            "Convergence achieved: improvement (%.2f%%) below threshold (%.2f%%). Stopping after %s iterations.",
            iteration_improvement * 100,
            optimizer.convergence_threshold * 100,
            round_index,
        )
        context.should_stop = True
        context.finish_reason = "no_improvement"
        optimizer._should_stop_optimization = True

    return iteration_improvement
