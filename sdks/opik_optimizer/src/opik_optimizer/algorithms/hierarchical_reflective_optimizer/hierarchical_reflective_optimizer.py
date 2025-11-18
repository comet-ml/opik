import logging
from typing import cast

import opik
from opik import opik_context
from opik.evaluation.evaluation_result import EvaluationResult
from opik.evaluation import evaluator as opik_evaluator

from typing import Any
from collections.abc import Callable
from ... import _llm_calls, helpers
from ...base_optimizer import BaseOptimizer
from ...api_objects import chat_prompt
from ...optimizable_agent import OptimizableAgent

from opik_optimizer.task_evaluator import _create_metric_class
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

    DEFAULT_ROUNDS = 10
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

    def _evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        optimization_id: str,
        n_samples: int | None = None,
        experiment_config: dict | None = None,
        **kwargs: Any,
    ) -> EvaluationResult:
        """
        Args:
            dataset: Opik Dataset to evaluate the prompt on
            metric: Metric functions
            use_full_dataset: Whether to use the full dataset or a subset
            experiment_config: Optional configuration for the experiment, useful to log additional metadata
            n_samples: Optional number of items to test in the dataset
            optimization_id: Optional ID of the optimization
            verbose: Controls internal logging/progress bars (0=off, 1=on).
        Returns:
            float: The evaluation score
        """
        logger.debug("Using full dataset for evaluation")

        configuration_updates = helpers.drop_none({"n_samples": n_samples})
        meta_metadata = helpers.drop_none(
            {"optimization_id": optimization_id, "stage": "trial_evaluation"}
        )
        experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=configuration_updates,
            additional_metadata={"meta_prompt": meta_metadata}
            if meta_metadata
            else None,
        )

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, str]:
            new_prompt = prompt.copy()
            messages = new_prompt.get_messages(dataset_item)
            new_prompt.set_messages(messages)
            agent = self.agent_class(prompt=new_prompt)

            try:
                logger.debug(
                    f"Calling LLM with prompt length: {sum(len(msg['content']) for msg in messages)}"
                )
                raw_model_output = agent.invoke(messages)
                logger.debug(f"LLM raw response length: {len(raw_model_output)}")
                logger.debug(f"LLM raw output: {raw_model_output}")
            except Exception as e:
                logger.error(f"Error calling model with prompt: {e}")
                logger.error(f"Failed prompt: {messages}")
                logger.error(
                    f"Prompt length: {sum(len(msg['content']) for msg in messages)}"
                )
                raise

            cleaned_model_output = raw_model_output.strip()

            # Add tags to trace for optimization tracking
            if self.current_optimization_id:
                opik_context.update_current_trace(
                    tags=[self.current_optimization_id, "Evaluation"]
                )

            result = {
                "llm_output": cleaned_model_output,
            }
            return result

        # Use dataset's get_items with limit for sampling
        logger.debug(
            f"Starting evaluation with {n_samples if n_samples else 'all'} samples for metric: {getattr(metric, '__name__', str(metric))}"
        )
        result = opik_evaluator.evaluate_optimization_trial(
            optimization_id=optimization_id,
            dataset=dataset,
            task=llm_task,
            scoring_metrics=[_create_metric_class(metric)],
            task_threads=self.n_threads,
            nb_samples=n_samples,
            experiment_config=experiment_config,
            verbose=self.verbose,
            project_name=self.project_name,
        )

        return result

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
        self, prompt: chat_prompt.ChatPrompt, root_cause: FailureMode, attempt: int = 1
    ) -> ImprovedPrompt:
        """
        Improve the prompt based on the root cause analysis.

        Args:
            prompt: Current prompt to improve
            root_cause: The failure mode to address
            attempt: Attempt number (1-indexed). Used to vary seed for retries.

        Returns:
            ImprovedPrompt with reasoning and improved messages
        """

        improve_prompt_prompt = IMPROVE_PROMPT_TEMPLATE.format(
            current_prompt=prompt.get_messages(),
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

        improve_prompt_response = _llm_calls.call_model(
            messages=[{"role": "user", "content": improve_prompt_prompt}],
            model=self.model,
            seed=attempt_seed,
            model_parameters=self.model_parameters,
            response_model=ImprovedPrompt,
        )

        return cast(ImprovedPrompt, improve_prompt_response)

    def _generate_and_evaluate_improvement(
        self,
        root_cause: FailureMode,
        best_prompt: chat_prompt.ChatPrompt,
        best_score: float,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable,
        optimization_id: str,
        n_samples: int | None,
        attempt: int,
        max_attempts: int,
    ) -> tuple[chat_prompt.ChatPrompt, float, EvaluationResult]:
        """
        Generate and evaluate a single improvement attempt for a failure mode.

        Args:
            root_cause: The failure mode to address
            best_prompt: The current best prompt to improve upon
            best_score: The current best score (for comparison)
            prompt: The original prompt (for metadata like name and tools)
            dataset: Dataset to evaluate on
            metric: Metric function
            optimization_id: ID of the optimization
            n_samples: Optional number of samples
            attempt: Current attempt number (1-indexed)
            max_attempts: Total number of attempts

        Returns:
            Tuple of (improved_prompt, improved_score, improved_experiment_result)
        """
        # Generate improvement with progress indication
        with reporting.display_prompt_improvement(
            failure_mode_name=root_cause.name, verbose=self.verbose
        ) as improvement_reporter:
            improved_prompt_response = self._improve_prompt(
                prompt=best_prompt, root_cause=root_cause, attempt=attempt
            )
            improvement_reporter.set_reasoning(improved_prompt_response.reasoning)

        # Convert to chat prompt
        messages_as_dicts = [x.model_dump() for x in improved_prompt_response.messages]

        improved_chat_prompt = chat_prompt.ChatPrompt(
            name=prompt.name,
            messages=messages_as_dicts,
            tools=best_prompt.tools,
            function_map=best_prompt.function_map,
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
            improved_experiment_result = self._evaluate_prompt(
                prompt=improved_chat_prompt,
                dataset=dataset,
                metric=metric,
                optimization_id=optimization_id,
                n_samples=n_samples,
            )

            improved_score = sum(
                [
                    x.score_results[0].value
                    for x in improved_experiment_result.test_results
                ]
            ) / len(improved_experiment_result.test_results)
            improved_reporter.set_score(improved_score)

        return improved_chat_prompt, improved_score, improved_experiment_result

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: opik.Dataset,
        metric: Callable[..., Any],
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        optimization_id: str | None = None,
        max_trials: int = DEFAULT_MAX_ITERATIONS,
        max_retries: int = 2,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Optimize a prompt using hierarchical reflective refinement.

        Args:
            prompt: The chat prompt to optimize.
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
        """
        # Reset counters at the start of optimization
        self._validate_optimization_inputs(
            prompt, dataset, metric, support_content_parts=True
        )

        self._reset_counters()
        self._should_stop_optimization = False  # Reset stop flag

        # Setup agent class
        self.agent_class = self._setup_agent_class(prompt, agent_class)

        # Set project name from parameter
        self.project_name = project_name

        optimization = self.opik_client.create_optimization(
            dataset_name=dataset.name,
            objective_name=getattr(metric, "__name__", str(metric)),
            name=self.name,
            metadata=self._build_optimization_config(),
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
            messages=prompt.get_messages(),
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "n_samples": n_samples,
                "auto_continue": auto_continue,
                "max_retries": max_retries,
                "max_trials": max_trials,
                "convergence_threshold": self.convergence_threshold,
            },
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
        )

        # First we will evaluate the prompt on the dataset
        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            experiment_result = self._evaluate_prompt(
                prompt=prompt,
                dataset=dataset,
                metric=metric,
                optimization_id=optimization.id,
                n_samples=n_samples,
            )

            avg_scores = sum(
                [x.score_results[0].value for x in experiment_result.test_results]
            ) / len(experiment_result.test_results)
            baseline_reporter.set_score(avg_scores)

        # Track baseline and best scores
        initial_score = avg_scores
        best_score = initial_score
        best_prompt = prompt
        best_messages = prompt.get_messages()
        initial_messages = list(
            prompt.get_messages()
        )  # Store copy of initial messages for diff

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
                    hierarchical_analysis = self._hierarchical_root_cause_analysis(
                        experiment_result
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
                    improved_chat_prompt = None
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
                            improved_chat_prompt,
                            improved_score,
                            improved_experiment_result,
                        ) = self._generate_and_evaluate_improvement(
                            root_cause=root_cause,
                            best_prompt=best_prompt,
                            best_score=best_score,
                            prompt=prompt,
                            dataset=dataset,
                            metric=metric,
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
                        and improved_chat_prompt is not None
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
                        best_prompt = improved_chat_prompt
                        best_messages = improved_chat_prompt.get_messages()
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

        # Display final optimization result with diff
        reporting.display_optimized_prompt_diff(
            initial_messages=initial_messages,
            optimized_messages=best_messages,
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

        # Prepare details for the result
        details = {
            "model": self.model,
            "temperature": (best_prompt.model_kwargs or {}).get("temperature")
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

        # Extract tool prompts if tools exist
        final_tools = getattr(best_prompt, "tools", None)
        tool_prompts = self._extract_tool_prompts(final_tools)

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=best_messages,
            score=best_score,
            metric_name=metric.__name__,
            initial_prompt=prompt.get_messages(),
            initial_score=initial_score,
            details=details,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            optimization_id=optimization.id,
            dataset_id=dataset.id,
            tool_prompts=tool_prompts,
        )
