import logging

import opik
from opik.evaluation.evaluation_result import EvaluationResult

from typing import Any
from ... import _llm_calls
from ...base_optimizer import BaseOptimizer
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...agents import OptimizableAgent, LiteLLMAgent

from opik_optimizer.optimization_result import OptimizationResult
from . import reporting
from .hierarchical_root_cause_analyzer import HierarchicalRootCauseAnalyzer
from .types import (
    FailureMode,
    ImprovedPrompt,
    HierarchicalRootCauseAnalysis,
)
from .prompts import IMPROVE_PROMPT_TEMPLATE

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
    """

    DEFAULT_MAX_ITERATIONS = 5
    DEFAULT_CONVERGENCE_THRESHOLD = 0.01  # Stop if improvement is less than 1%

    def __init__(
        self,
        model: str = "gpt-4o",
        model_parameters: dict[str, Any] | None = None,
        max_parallel_batches: int = 5,
        batch_size: int = 25,
        convergence_threshold: float = DEFAULT_CONVERGENCE_THRESHOLD,
        n_threads: int = 12,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
    ):
        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
        )
        self.n_threads = n_threads
        self.max_parallel_batches = max_parallel_batches
        self.batch_size = batch_size
        self.convergence_threshold = convergence_threshold
        self._should_stop_optimization = False  # Flag to exit all loops

        # Initialize hierarchical analyzer
        self._hierarchical_analyzer = HierarchicalRootCauseAnalyzer(
            reasoning_model=self.model,
            seed=self.seed,
            max_parallel_batches=self.max_parallel_batches,
            batch_size=self.batch_size,
            verbose=self.verbose,
            model_parameters=self.model_parameters,
        )

    def get_optimizer_metadata(self) -> dict[str, Any]:
        """
        Get metadata about the optimizer configuration.

        Returns:
            Dictionary containing optimizer-specific configuration
        """
        return {
            "model": self.model,
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
    ) -> dict[str, ImprovedPrompt]:
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

        improve_prompt_prompt = IMPROVE_PROMPT_TEMPLATE.format(
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
            model=self.model,
            seed=attempt_seed,
            model_parameters=self.model_parameters,
            response_model=DynamicImprovedPromptsResponse,
        )

        # Extract improved prompts from response
        improved_prompts = {}
        for prompt_name in prompts.keys():
            improved_prompts[prompt_name] = getattr(
                improve_prompt_response, prompt_name
            )

        return improved_prompts

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
        optimization_id: str,
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

        # Generate improvement with progress indication
        with reporting.display_prompt_improvement(
            failure_mode_name=root_cause.name, verbose=self.verbose
        ) as improvement_reporter:
            improved_prompts_response = self._improve_prompt(
                prompts=best_prompts, root_cause=root_cause, attempt=attempt
            )
            # Display reasoning from first prompt
            first_reasoning = list(improved_prompts_response.values())[0].reasoning
            improvement_reporter.set_reasoning(first_reasoning)

        # Convert ImprovedPrompt dict to ChatPrompt dict
        improved_chat_prompts = {}
        for prompt_name, improved_prompt in improved_prompts_response.items():
            messages_as_dicts = [x.model_dump() for x in improved_prompt.messages]
            original = original_prompts[prompt_name]

            improved_chat_prompts[prompt_name] = chat_prompt.ChatPrompt(
                name=original.name,
                messages=messages_as_dicts,
                tools=best_prompts[prompt_name].tools,
                function_map=best_prompts[prompt_name].function_map,
            )

        # Evaluate improved prompt
        eval_message = f"Evaluating improvement for failure mode '{root_cause.name}'"
        if max_attempts > 1:
            eval_message += f" (attempt {attempt}/{max_attempts})"
        eval_message += ":"

        with reporting.display_evaluation(
            message=eval_message,
            verbose=self.verbose,
            indent="│   ",
            baseline_score=best_score,  # Pass baseline for comparison
        ) as improved_reporter:
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
            improved_reporter.set_score(improved_score)

        return improved_chat_prompts, improved_score, improved_experiment_result

    def optimize_prompt(  # type: ignore[override]
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: opik.Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        validation_dataset: opik.Dataset | None = None,
        max_trials: int = DEFAULT_MAX_ITERATIONS,
        max_retries: int = 2,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Optimize a prompt using hierarchical reflective refinement.

        Args:
            prompt: The chat prompt(s) to optimize. Can be a single ChatPrompt or a dict of ChatPrompts.
            dataset: Dataset containing evaluation examples.
            metric: Callable that scores `(dataset_item, llm_output)`.

        Optional Arguments:
            experiment_config: Additional configuration for experiment logging.
            n_samples: Number of dataset samples to evaluate per prompt (None for all).
            auto_continue: Whether to continue optimization automatically after each round.
            agent_class: Optional agent implementation to execute prompt evaluations.
            project_name: Opik project name for trace logging (default: "Optimization").
            optimization_id: Optional ID for the Opik optimization run; when provided it must
                be a valid UUIDv7 string.
            max_trials: Maximum number of optimization iterations to run.
            max_retries: Maximum retries allowed for addressing a failure mode.
            validation_dataset: Optional validation dataset for evaluating candidates. When provided,
                the optimizer uses the training dataset for understanding failure modes and generating
                improvements, then evaluates candidates on the validation dataset to prevent overfitting.
        """
        # Convert single prompt to dict for internal processing
        optimizable_prompts: dict[str, chat_prompt.ChatPrompt]
        if isinstance(prompt, chat_prompt.ChatPrompt):
            optimizable_prompts = {prompt.name: prompt}
            is_single_prompt_optimization = True
        else:
            optimizable_prompts = prompt
            is_single_prompt_optimization = False

        # Reset counters at the start of optimization
        self._validate_optimization_inputs(
            optimizable_prompts, dataset, metric, support_content_parts=True
        )

        self._reset_counters()
        self._should_stop_optimization = False  # Reset stop flag

        # Set project name from parameter
        self.project_name = project_name

        optimization = self.opik_client.create_optimization(
            dataset_name=dataset.name,
            objective_name=metric.__name__,
            metadata=self._build_optimization_metadata(),
            name=self.name,
            optimization_id=optimization_id,
        )
        self.current_optimization_id = optimization.id
        logger.debug(f"Created optimization with ID: {optimization.id}")

        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=optimization.id if optimization is not None else None,
            dataset_id=dataset.id,
            verbose=self.verbose,
        )
        reporting.display_configuration(
            messages=optimizable_prompts,
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "n_samples": n_samples,
                "auto_continue": auto_continue,
                "max_retries": max_retries,
                "max_trials": max_trials,
                "convergence_threshold": self.convergence_threshold,
            },
            verbose=self.verbose,
        )

        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        # Create agent for prompt execution
        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)

        # First we will evaluate the prompt on the dataset
        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            experiment_result = self.evaluate_prompt(
                prompt=optimizable_prompts,
                dataset=evaluation_dataset,  # use right dataset for scoring
                metric=metric,
                agent=agent,
                n_samples=n_samples,
                n_threads=self.n_threads,
                return_evaluation_result=True,
            )

            avg_scores = sum(
                [x.score_results[0].value for x in experiment_result.test_results]
            ) / len(experiment_result.test_results)
            baseline_reporter.set_score(avg_scores)

        # Track baseline and best scores
        initial_score = avg_scores
        best_score = initial_score
        best_prompts = optimizable_prompts

        # Multi-iteration optimization loop
        iteration = 0
        previous_iteration_score = initial_score
        trials_used = 0

        while trials_used < max_trials:
            iteration += 1
            logger.info(
                f"Starting iteration {iteration} (trials: {trials_used}/{max_trials})"
            )

            # Check if we should stop (flag set by inner loops)
            if self._should_stop_optimization:
                logger.info(
                    f"Stopping optimization: reached max_trials limit ({max_trials})."
                )
                break

            with reporting.display_optimization_iteration(
                iteration=iteration, verbose=self.verbose
            ) as iteration_reporter:
                # Perform hierarchical root cause analysis
                with reporting.display_root_cause_analysis(
                    verbose=self.verbose
                ) as analysis_reporter:
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
                    analysis_reporter.set_completed(
                        total_test_cases=hierarchical_analysis.total_test_cases,
                        num_batches=hierarchical_analysis.num_batches,
                    )

                # Display hierarchical synthesis and failure modes
                if self.verbose:
                    reporting.display_hierarchical_synthesis(
                        total_test_cases=hierarchical_analysis.total_test_cases,
                        num_batches=hierarchical_analysis.num_batches,
                        synthesis_notes=hierarchical_analysis.synthesis_notes,
                        verbose=self.verbose,
                    )

                reporting.display_failure_modes(
                    failure_modes=hierarchical_analysis.unified_failure_modes,
                    verbose=self.verbose,
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
                        if trials_used >= max_trials:
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
                            optimization_id=optimization.id,
                            n_samples=n_samples,
                            attempt=attempt,
                            max_attempts=max_attempts,
                        )
                        trials_used += 1

                        # Check if we got improvement
                        if improved_score > best_score:
                            logger.info(
                                f"Improvement found for '{root_cause.name}' on attempt {attempt}"
                            )
                            break

                        # No improvement - should we retry?
                        if attempt < max_attempts and trials_used < max_trials:
                            reporting.display_retry_attempt(
                                attempt=attempt,
                                max_attempts=max_attempts,
                                failure_mode_name=root_cause.name,
                                verbose=self.verbose,
                            )
                        else:
                            logger.debug(
                                f"No improvement after {attempt} attempts for '{root_cause.name}'"
                            )

                    # Break out of failure mode loop if flag is set
                    if self._should_stop_optimization:
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

                        # Display improvement for this iteration
                        reporting.display_iteration_improvement(
                            improvement=improvement,
                            current_score=improved_score,
                            best_score=best_score,
                            verbose=self.verbose,
                        )

                        # Update best
                        best_score = improved_score
                        best_prompts = improved_chat_prompts
                        experiment_result = improved_experiment_result
                        logger.info(
                            f"Updated best prompt after addressing '{root_cause.name}'"
                        )
                    else:
                        logger.debug(
                            f"Keeping previous best prompt, no improvement from '{root_cause.name}'"
                        )

                # Mark iteration complete
                improved_since_start = best_score > initial_score
                iteration_reporter.iteration_complete(
                    best_score=best_score, improved=improved_since_start
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
                break

            # Update previous score for next iteration
            previous_iteration_score = best_score

        # Display final optimization result with diff for all prompts
        for prompt_name in optimizable_prompts:
            initial_prompt = optimizable_prompts[prompt_name]
            best_prompt = best_prompts[prompt_name]
            initial_messages = list(initial_prompt.get_messages())
            optimized_messages = list(best_prompt.get_messages())
            reporting.display_optimized_prompt_diff(
                prompt_name=prompt_name,
                initial_messages=initial_messages,
                optimized_messages=optimized_messages,
                initial_score=initial_score,
                best_score=best_score,
                verbose=self.verbose,
            )

        # Update optimization status to completed
        try:
            optimization.update(status="completed")
            logger.info(f"Optimization {optimization.id} status updated to completed.")
        except Exception as e:
            logger.warning(f"Failed to update optimization status: {e}")

        # Convert result format based on input type
        result_best_prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        result_initial_prompt: (
            chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]
        )
        if is_single_prompt_optimization:
            result_best_prompt = list(best_prompts.values())[0]
            result_initial_prompt = list(optimizable_prompts.values())[0]
        else:
            result_best_prompt = best_prompts
            result_initial_prompt = optimizable_prompts

        # Prepare details for the result
        first_best_prompt = list(best_prompts.values())[0]
        details = {
            "model": self.model,
            "temperature": (first_best_prompt.model_kwargs or {}).get("temperature")
            or self.model_parameters.get("temperature"),
            "n_threads": self.n_threads,
            "max_parallel_batches": self.max_parallel_batches,
            "max_retries": max_retries,
            "n_samples": n_samples,
            "auto_continue": auto_continue,
            "max_trials": max_trials,
            "convergence_threshold": self.convergence_threshold,
            "iterations_completed": iteration,
            "trials_used": trials_used,
        }

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=result_best_prompt,
            score=best_score,
            metric_name=metric.__name__,
            initial_prompt=result_initial_prompt,
            initial_score=initial_score,
            details=details,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            optimization_id=optimization.id,
            dataset_id=dataset.id,
        )
