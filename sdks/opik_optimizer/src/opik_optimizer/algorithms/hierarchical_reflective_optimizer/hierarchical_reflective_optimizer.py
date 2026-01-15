import logging

import opik
from opik.evaluation.evaluation_result import EvaluationResult

from typing import Any
from ... import _llm_calls
from ...base_optimizer import BaseOptimizer, OptimizationContext, AlgorithmResult
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...agents import OptimizableAgent
from ...utils.prompt_library import PromptOverrides

from .hierarchical_root_cause_analyzer import HierarchicalRootCauseAnalyzer
from .types import (
    FailureMode,
    ImprovedPrompt,
    HierarchicalRootCauseAnalysis,
)
from . import prompts as hierarchical_prompts

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging


class HierarchicalReflectiveOptimizer(BaseOptimizer):
    """
    The Hierarchical Reflective Optimizer uses hierarchical root cause analysis to improve prompts
    based on failure modes identified during the evaluation process.

    This algorithm uses a two-stage hierarchical approach: analyzing failures in batches and then
    synthesizing findings to identify unified failure modes. It's best suited when you have a
    complex prompt that you want to systematically refine based on understanding why it fails.

    Args:
        model: LiteLLM model name for the optimization algorithm (reasoning and analysis)
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
        max_parallel_batches: Maximum number of batches to process concurrently during
            hierarchical root cause analysis
        batch_size: Number of test cases per batch for root cause analysis
        convergence_threshold: Stop if relative improvement is below this threshold
        n_threads: Number of parallel threads for evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
        prompt_overrides: Optional dict or callable to override/customize prompt templates.
            If a dict, keys should match DEFAULT_PROMPTS keys.
            If a callable, receives the PromptLibrary instance for in-place modification.
    """

    DEFAULT_PROMPTS: dict[str, str] = {
        "batch_analysis_prompt": hierarchical_prompts.BATCH_ANALYSIS_PROMPT,
        "synthesis_prompt": hierarchical_prompts.SYNTHESIS_PROMPT,
        "improve_prompt_template": hierarchical_prompts.IMPROVE_PROMPT_TEMPLATE,
    }

    DEFAULT_MAX_ITERATIONS = 5
    DEFAULT_CONVERGENCE_THRESHOLD = 0.01  # Stop if improvement is less than 1%

    # Reuse existing optimization run when optimization_id is provided
    REUSE_EXISTING_OPTIMIZATION: bool = True

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        reasoning_model: str | None = None,
        reasoning_model_parameters: dict[str, Any] | None = None,
        max_parallel_batches: int = 5,
        batch_size: int = 25,
        convergence_threshold: float = DEFAULT_CONVERGENCE_THRESHOLD,
        n_threads: int = 12,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = True,
        perfect_score: float = 0.95,
    ):
        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            reasoning_model=reasoning_model,
            reasoning_model_parameters=reasoning_model_parameters,
            name=name,
            skip_perfect_score=skip_perfect_score,
            perfect_score=perfect_score,
            prompt_overrides=prompt_overrides,
        )
        self.n_threads = n_threads
        self.max_parallel_batches = max_parallel_batches
        self.batch_size = batch_size
        self.convergence_threshold = convergence_threshold
        self._should_stop_optimization = False  # Flag to exit all loops

        # Initialize hierarchical analyzer
        self._hierarchical_analyzer = HierarchicalRootCauseAnalyzer(
            reasoning_model=self.reasoning_model,
            seed=self.seed,
            max_parallel_batches=self.max_parallel_batches,
            batch_size=self.batch_size,
            verbose=self.verbose,
            model_parameters=self.reasoning_model_parameters,
            prompts=self._prompts,
        )

    def get_optimizer_metadata(self) -> dict[str, Any]:
        """
        Get metadata about the optimizer configuration.

        Returns:
            Dictionary containing optimizer-specific configuration
        """
        return {
            "model": self.model,
            "reasoning_model": self.reasoning_model,
            "n_threads": self.n_threads,
            "max_parallel_batches": self.max_parallel_batches,
            "convergence_threshold": self.convergence_threshold,
            "seed": self.seed,
            "verbose": self.verbose,
        }

    def _calculate_improvement(
        self, current_score: float, previous_score: float
    ) -> float:
        """Calculate the improvement percentage between scores."""
        return (
            (current_score - previous_score) / previous_score
            if previous_score > 0
            else 0
        )

    def _hierarchical_root_cause_analysis(
        self, evaluation_result: EvaluationResult
    ) -> HierarchicalRootCauseAnalysis:
        """
        Perform hierarchical root cause analysis on evaluation results.

        This method uses a two-stage hierarchical approach:
        1. Split results into batches and analyze each batch
        2. Synthesize batch analyses into unified failure modes

        Args:
            evaluation_result: The evaluation result to analyze

        Returns:
            HierarchicalRootCauseAnalysis containing batch analyses and overall synthesis
        """
        logger.debug("Performing hierarchical root cause analysis...")
        return self._hierarchical_analyzer.analyze(
            evaluation_result, project_name=self.project_name
        )

    def _improve_prompt(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        root_cause: FailureMode,
        attempt: int = 1,
    ) -> dict[str, ImprovedPrompt] | list[dict[str, ImprovedPrompt]]:
        """
        Improve all prompts in the dict based on the root cause analysis.

        Makes a single LLM call to improve all prompts at once for efficiency.

        Args:
            prompts: Dictionary of prompts to improve
            root_cause: The failure mode to address
            attempt: Attempt number (1-indexed). Used to vary seed for retries.

        Returns:
            Dictionary mapping prompt names to ImprovedPrompt objects
        """
        # Format all prompts into a single section
        prompts_section = ""
        for prompt_name, prompt in prompts.items():
            prompts_section += f"\n--- Prompt: {prompt_name} ---\n"
            prompts_section += f"```\n{prompt.get_messages()}\n```\n"

        improve_prompt_template = self.get_prompt("improve_prompt_template")
        improve_prompt_prompt = improve_prompt_template.format(
            prompts_section=prompts_section,
            failure_mode_name=root_cause.name,
            failure_mode_description=root_cause.description,
            failure_mode_root_cause=root_cause.root_cause,
        )

        # Vary seed based on attempt to avoid cache hits and ensure different results
        # Each attempt gets a unique seed: base_seed, base_seed+1000, base_seed+2000, etc.
        attempt_seed = self.seed + (attempt - 1) * 1000

        if attempt > 1:
            logger.debug(
                f"Retry attempt {attempt}: Using seed {attempt_seed} (base seed: {self.seed})"
            )

        # Create dynamic response model for all prompts
        from . import types as hierarchical_types

        DynamicImprovedPromptsResponse = (
            hierarchical_types.create_improved_prompts_response_model(
                prompt_names=list(prompts.keys())
            )
        )

        improve_prompt_response = _llm_calls.call_model(
            messages=[{"role": "user", "content": improve_prompt_prompt}],
            model=self.reasoning_model,
            seed=attempt_seed,
            model_parameters=self.reasoning_model_parameters,
            response_model=DynamicImprovedPromptsResponse,
            project_name=self.project_name,
        )

        # Extract improved prompts from response
        responses = (
            improve_prompt_response
            if isinstance(improve_prompt_response, list)
            else [improve_prompt_response]
        )

        improved_prompts_candidates: list[dict[str, ImprovedPrompt]] = []
        for response_item in responses:
            improved_prompts: dict[str, ImprovedPrompt] = {}
            for prompt_name in prompts.keys():
                improved_prompts[prompt_name] = getattr(response_item, prompt_name)
            improved_prompts_candidates.append(improved_prompts)

        if len(improved_prompts_candidates) == 1:
            return improved_prompts_candidates[0]
        return improved_prompts_candidates

    def _generate_and_evaluate_improvement(
        self,
        root_cause: FailureMode,
        best_prompts: dict[str, chat_prompt.ChatPrompt],
        best_score: float,
        original_prompts: dict[str, chat_prompt.ChatPrompt],
        dataset: opik.Dataset,
        validation_dataset: opik.Dataset | None,
        metric: MetricFunction,
        agent: OptimizableAgent,
        optimization_id: str | None,
        n_samples: int | None,
        attempt: int,
        max_attempts: int,
    ) -> tuple[dict[str, chat_prompt.ChatPrompt], float, EvaluationResult]:
        """
        Generate and evaluate a single improvement attempt for a failure mode.

        Now works with dict of prompts and makes a single LLM call to improve all.

        Args:
            root_cause: The failure mode to address
            best_prompts: The current best prompts to improve upon
            best_score: The current best score (for comparison)
            original_prompts: The original prompts (for metadata like name and tools)
            dataset: Dataset to evaluate on
            validation_dataset: Optional validation dataset
            metric: Metric function
            agent: Agent for executing prompts
            optimization_id: ID of the optimization
            n_samples: Optional number of samples
            attempt: Current attempt number (1-indexed)
            max_attempts: Total number of attempts

        Returns:
            Tuple of (improved_prompts_dict, improved_score, improved_experiment_result)
        """

        # Logic on which dataset to use for scoring
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        # Generate improvement
        logger.debug(f"Generating improvement for failure mode: {root_cause.name}")
        improved_prompts_response = self._improve_prompt(
            prompts=best_prompts, root_cause=root_cause, attempt=attempt
        )

        improved_chat_prompts_candidates: list[dict[str, chat_prompt.ChatPrompt]] = []
        candidate_reasonings: list[str] = []
        if isinstance(improved_prompts_response, list):
            responses = improved_prompts_response
        else:
            responses = [improved_prompts_response]

        for response_item in responses:
            if not response_item:
                continue
            improved_chat_prompts: dict[str, chat_prompt.ChatPrompt] = {}
            response_reasoning = ""
            for prompt_name, improved_prompt in response_item.items():
                if not response_reasoning:
                    response_reasoning = improved_prompt.reasoning
                messages_as_dicts = [x.model_dump() for x in improved_prompt.messages]
                original = original_prompts[prompt_name]

                improved_chat_prompts[prompt_name] = chat_prompt.ChatPrompt(
                    name=original.name,
                    messages=messages_as_dicts,
                    tools=best_prompts[prompt_name].tools,
                    function_map=best_prompts[prompt_name].function_map,
                    model=original.model,
                    model_parameters=original.model_kwargs,
                )
            improved_chat_prompts_candidates.append(improved_chat_prompts)
            candidate_reasonings.append(response_reasoning)

        # Evaluate improved prompt
        logger.debug(
            f"Evaluating improvement for failure mode '{root_cause.name}' "
            f"(attempt {attempt}/{max_attempts})"
        )

        if not improved_chat_prompts_candidates:
            fallback_result = self.evaluate_prompt(
                prompt=best_prompts,
                dataset=evaluation_dataset,
                metric=metric,
                agent=agent,
                n_samples=n_samples,
                n_threads=self.n_threads,
                return_evaluation_result=True,
            )
            fallback_scores = [
                x.score_results[0].value for x in fallback_result.test_results
            ]
            fallback_score = (
                sum(fallback_scores) / len(fallback_scores)
                if fallback_scores
                else best_score
            )
            return best_prompts, fallback_score, fallback_result

        best_prompt_bundle = improved_chat_prompts_candidates[0]
        best_result = self.evaluate_prompt(
            prompt=best_prompt_bundle,
            dataset=evaluation_dataset,  # use right dataset for scoring
            metric=metric,
            agent=agent,
            n_samples=n_samples,
            n_threads=self.n_threads,
            return_evaluation_result=True,
        )
        best_score_local = sum(
            [x.score_results[0].value for x in best_result.test_results]
        ) / len(best_result.test_results)

        # Evaluate remaining candidates and keep the best-scoring bundle.
        for idx, improved_chat_prompts in enumerate(
            improved_chat_prompts_candidates[1:], start=1
        ):
            improved_experiment_result = self.evaluate_prompt(
                prompt=improved_chat_prompts,
                dataset=evaluation_dataset,  # use right dataset for scoring
                metric=metric,
                agent=agent,
                n_samples=n_samples,
                n_threads=self.n_threads,
                return_evaluation_result=True,
            )

            improved_score = sum(
                [
                    x.score_results[0].value
                    for x in improved_experiment_result.test_results
                ]
            ) / len(improved_experiment_result.test_results)

            if improved_score > best_score_local:
                best_score_local = improved_score
                best_prompt_bundle = improved_chat_prompts
                best_result = improved_experiment_result

        return best_prompt_bundle, best_score_local, best_result

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer-specific configuration for display."""
        return {
            "optimizer": self.__class__.__name__,
            "n_samples": context.n_samples,
            "auto_continue": context.extra_params.get("auto_continue", False),
            "max_retries": context.extra_params.get("max_retries", 2),
            "max_trials": context.max_trials,
            "convergence_threshold": self.convergence_threshold,
        }

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return HierarchicalReflective-specific metadata for the optimization result.

        Provides iterations and trials tracking that can be used in any scenario
        (early stop, completion, etc.). The optimizer doesn't know why this
        is being called - it just provides its current state.
        """
        return {
            "trials_completed": context.trials_completed,
            "rounds_completed": context.trials_completed,
            "iterations_completed": context.trials_completed,
            "convergence_threshold": self.convergence_threshold,
        }

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult:
        optimizable_prompts = context.prompts
        dataset = context.dataset
        validation_dataset = context.validation_dataset
        metric = context.metric
        agent = context.agent
        n_samples = context.n_samples
        max_trials = context.max_trials
        max_retries = context.extra_params.get("max_retries", 2)
        optimization = context.optimization

        initial_score = float(context.baseline_score or 0.0)
        best_score = initial_score
        best_prompts = optimizable_prompts

        iteration = 0
        previous_iteration_score: float = initial_score

        while context.trials_completed < max_trials:
            # Check should_stop flag at start of each iteration
            if context.should_stop:
                break

            iteration += 1
            logger.info(
                f"Starting iteration {iteration} (trials: {context.trials_completed}/{max_trials})"
            )

            if self._should_stop_optimization:
                logger.info(
                    f"Stopping optimization: reached max_trials limit ({max_trials})."
                )
                context.should_stop = True
                context.finish_reason = "max_trials"
                break

            # Perform hierarchical root cause analysis
            logger.debug("Performing hierarchical root cause analysis...")
            train_dataset_experiment_result = self.evaluate_prompt(
                prompt=best_prompts,
                dataset=dataset,
                metric=metric,
                agent=agent,
                n_samples=n_samples,
                n_threads=self.n_threads,
                return_evaluation_result=True,
            )
            hierarchical_analysis = self._hierarchical_root_cause_analysis(
                train_dataset_experiment_result
            )
            logger.debug(
                f"Root cause analysis complete: {hierarchical_analysis.total_test_cases} test cases, "
                f"{hierarchical_analysis.num_batches} batches"
            )

            logger.debug(
                f"Identified {len(hierarchical_analysis.unified_failure_modes)} failure modes"
            )

            # Generate improved prompt for each failure mode
            for idx, root_cause in enumerate(
                hierarchical_analysis.unified_failure_modes, 1
            ):
                logger.debug(
                    f"Addressing failure mode {idx}/{len(hierarchical_analysis.unified_failure_modes)}: {root_cause.name}"
                )

                # Try multiple attempts if needed
                max_attempts = max_retries + 1
                improved_chat_prompts = None
                improved_score = None

                for attempt in range(1, max_attempts + 1):
                    # Check if we've reached the trial limit before starting a new trial
                    if context.trials_completed >= max_trials:
                        logger.info(
                            f"Reached max_trials limit ({max_trials}) during failure mode '{root_cause.name}'. "
                            f"Stopping optimization."
                        )
                        self._should_stop_optimization = True
                        break

                    # Generate and evaluate improvement (this is 1 trial)
                    (
                        improved_chat_prompts,
                        improved_score,
                        improved_experiment_result,
                    ) = self._generate_and_evaluate_improvement(
                        root_cause=root_cause,
                        best_prompts=best_prompts,
                        best_score=best_score,
                        original_prompts=optimizable_prompts,
                        dataset=dataset,
                        validation_dataset=validation_dataset,
                        metric=metric,
                        agent=agent,
                        optimization_id=optimization.id if optimization else None,
                        n_samples=n_samples,
                        attempt=attempt,
                        max_attempts=max_attempts,
                    )
                    # Increment context.trials_completed (single source of truth)
                    context.trials_completed += 1

                    # Check for perfect score early stop
                    if self.skip_perfect_score and improved_score >= self.perfect_score:
                        context.should_stop = True
                        context.finish_reason = "perfect_score"
                        break

                    # Check if we got improvement
                    if improved_score > best_score:
                        logger.info(
                            f"Improvement found for '{root_cause.name}' on attempt {attempt}"
                        )
                        break

                    # No improvement - should we retry?
                    if attempt < max_attempts and context.trials_completed < max_trials:
                        logger.debug(
                            f"Retrying attempt {attempt + 1}/{max_attempts} for '{root_cause.name}'"
                        )
                    else:
                        logger.debug(
                            f"No improvement after {attempt} attempts for '{root_cause.name}'"
                        )

                # Break out of failure mode loop if flag is set
                if self._should_stop_optimization or context.should_stop:
                    break

                # Check if final result is an improvement
                if (
                    improved_score is not None
                    and improved_chat_prompts is not None
                    and improved_score > best_score
                ):
                    improvement = self._calculate_improvement(
                        improved_score, best_score
                    )
                    logger.info(
                        f"Improvement {improvement:.2%}: {best_score:.4f} -> {improved_score:.4f}"
                    )

                    # Update best
                    best_score = improved_score
                    best_prompts = improved_chat_prompts
                    logger.info(
                        f"Updated best prompt after addressing '{root_cause.name}'"
                    )
                else:
                    logger.debug(
                        f"Keeping previous best prompt, no improvement from '{root_cause.name}'"
                    )

            # Check for convergence after iteration
            iteration_improvement = self._calculate_improvement(
                best_score, previous_iteration_score
            )

            logger.info(
                f"Iteration {iteration} complete. Score: {best_score:.4f}, "
                f"Improvement: {iteration_improvement:.2%}"
            )

            # Stop if improvement is below convergence threshold
            if abs(iteration_improvement) < self.convergence_threshold:
                logger.info(
                    f"Convergence achieved: improvement ({iteration_improvement:.2%}) "
                    f"below threshold ({self.convergence_threshold:.2%}). "
                    f"Stopping after {iteration} iterations."
                )
                # Do not break early; continue until max_trials are exhausted for stubborn cases

            # Update previous score for next iteration
            previous_iteration_score = best_score

        # finish_reason, stopped_early, stop_reason are handled by base class
        return AlgorithmResult(
            best_prompts=best_prompts,
            best_score=best_score,
            history=[],  # HierarchicalReflective doesn't track per-round history
            metadata={
                # Algorithm-specific fields only (framework fields handled by base)
                "n_threads": self.n_threads,
                "max_parallel_batches": self.max_parallel_batches,
                "max_retries": max_retries,
                "convergence_threshold": self.convergence_threshold,
                "iterations_completed": iteration,
            },
        )
