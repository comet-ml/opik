import logging

import opik
from opik.evaluation.evaluation_result import EvaluationResult

from typing import Any
from ...core import llm_calls as _llm_calls
from ...core import runtime
from ...base_optimizer import BaseOptimizer
from ...core.state import OptimizationContext, AlgorithmResult
from ... import constants
from ...api_objects import chat_prompt
from ...api_objects.types import Content
from ...api_objects.types import MetricFunction
from ...agents import OptimizableAgent
from ...utils.prompt_library import PromptOverrides
from ...utils.prompt_roles import apply_role_constraints, count_disallowed_role_updates
from ...utils.toolcalling.ops import toolcalling as toolcalling_utils
from ...utils.toolcalling.core import segment_updates

from .rootcause_ops import HierarchicalRootCauseAnalyzer
from .types import (
    FailureMode,
    ImprovedPrompt,
    HierarchicalRootCauseAnalysis,
)
from . import prompts as hierarchical_prompts
from . import reporting
from .ops import iteration_ops

# Set up logging
logger = logging.getLogger(__name__)  # Gets logger configured by setup_logging


def _message_has_content(message: dict[str, Any]) -> bool:
    """Return True if the message carries non-empty content."""
    content: Content | None = message.get("content")
    if content is None:
        return False
    return chat_prompt.ChatPrompt._has_non_empty_content(content)


class HierarchicalReflectiveOptimizer(BaseOptimizer):
    supports_tool_optimization: bool = True
    supports_prompt_optimization: bool = True
    supports_multimodal: bool = True
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

    DEFAULT_MAX_ITERATIONS = constants.HRO_DEFAULT_MAX_ITERATIONS
    DEFAULT_CONVERGENCE_THRESHOLD = constants.HRO_DEFAULT_CONVERGENCE_THRESHOLD

    def __init__(
        self,
        model: str = constants.DEFAULT_MODEL,
        model_parameters: dict[str, Any] | None = None,
        reasoning_model: str | None = None,
        reasoning_model_parameters: dict[str, Any] | None = None,
        max_parallel_batches: int = constants.HRO_DEFAULT_MAX_PARALLEL_BATCHES,
        batch_size: int = constants.HRO_DEFAULT_BATCH_SIZE,
        convergence_threshold: float = constants.HRO_DEFAULT_CONVERGENCE_THRESHOLD,
        n_threads: int = constants.DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = constants.DEFAULT_SEED,
        name: str | None = None,
        prompt_overrides: PromptOverrides = None,
        skip_perfect_score: bool = constants.DEFAULT_SKIP_PERFECT_SCORE,
        perfect_score: float = constants.DEFAULT_PERFECT_SCORE,
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
        self._iterations_completed = 0
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

    def _record_trial_score(
        self,
        context: OptimizationContext,
        score: float,
        prompts: dict[str, chat_prompt.ChatPrompt],
    ) -> None:
        """Update trial counters and best score tracking for one evaluation.

        Centralizes trial accounting so hierarchy-specific evaluation calls can
        keep BaseOptimizer stop logic aligned with actual scoring events.
        """
        coerced_score = self._coerce_score(score)
        self.on_trial(
            context=context,
            prompts=prompts,
            score=coerced_score,
            prev_best_score=context.current_best_score,
        )
        self._should_stop_context(context)

    def _evaluate_prompts_with_result(
        self,
        *,
        prompts: dict[str, chat_prompt.ChatPrompt],
        dataset: opik.Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None,
        n_samples: int | float | str | None,
        context: OptimizationContext,
        empty_score: float | None = None,
        sampling_tag: str | None = None,
    ) -> tuple[float, EvaluationResult]:
        """Evaluate prompts, update trial state, and return score + result."""
        return self.evaluate_with_result(
            context=context,
            prompts=prompts,
            experiment_config=context.experiment_config,
            empty_score=empty_score,
            n_samples=n_samples,
            n_samples_strategy=context.n_samples_strategy,
            sampling_tag=sampling_tag,
        )

    def _improve_prompt(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        root_cause: FailureMode,
        attempt: int = 1,
        *,
        optimize_tools: bool = False,
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
            if optimize_tools:
                tool_blocks = toolcalling_utils.build_tool_blocks_from_prompt(prompt)
                if tool_blocks:
                    prompts_section += f"\nTools:\n{tool_blocks}\n"

        improve_prompt_template = self.get_prompt("improve_prompt_template")
        tool_instructions = (
            hierarchical_prompts.TOOL_INSTRUCTIONS if optimize_tools else ""
        )
        improve_prompt_prompt = improve_prompt_template.format(
            prompts_section=prompts_section,
            failure_mode_name=root_cause.name,
            failure_mode_description=root_cause.description,
            failure_mode_root_cause=root_cause.root_cause,
            tool_instructions=tool_instructions,
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
        agent: OptimizableAgent | None,
        optimization_id: str | None,
        n_samples: int | float | str | None,
        attempt: int,
        max_attempts: int,
        context: OptimizationContext,
        round_handle: Any,
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
            round_handle: Active round handle for history recording

        Returns:
            Tuple of (improved_prompts_dict, improved_score, improved_experiment_result)
        """
        # Logic on which dataset to use for scoring
        evaluation_dataset = (
            validation_dataset if validation_dataset is not None else dataset
        )

        # Generate improvement
        logger.debug(f"Generating improvement for failure mode: {root_cause.name}")
        optimize_tools = bool(context.extra_params.get("optimize_tools"))
        improved_prompts_response = self._improve_prompt(
            prompts=best_prompts,
            root_cause=root_cause,
            attempt=attempt,
            optimize_tools=optimize_tools,
        )
        improved_chat_prompts_candidates = self._build_improved_candidates(
            improved_prompts_response,
            original_prompts,
            best_prompts,
            context,
        )
        logger.debug(
            "Evaluating improvement for failure mode '%s' (attempt %s/%s)",
            root_cause.name,
            attempt,
            max_attempts,
        )
        if not improved_chat_prompts_candidates:
            return self._fallback_improvement(
                best_prompts=best_prompts,
                evaluation_dataset=evaluation_dataset,
                metric=metric,
                agent=agent,
                n_samples=n_samples,
                context=context,
                best_score=best_score,
                round_handle=round_handle,
            )
        return self._evaluate_improved_candidates(
            improved_chat_prompts_candidates=improved_chat_prompts_candidates,
            evaluation_dataset=evaluation_dataset,
            metric=metric,
            agent=agent,
            n_samples=n_samples,
            context=context,
            round_handle=round_handle,
        )

    def _build_improved_candidates(
        self,
        improved_prompts_response: Any,
        original_prompts: dict[str, chat_prompt.ChatPrompt],
        best_prompts: dict[str, chat_prompt.ChatPrompt],
        context: OptimizationContext,
    ) -> list[dict[str, chat_prompt.ChatPrompt]]:
        """Build ChatPrompt candidates from the improvement response."""
        improved_chat_prompts_candidates: list[dict[str, chat_prompt.ChatPrompt]] = []
        responses = (
            improved_prompts_response
            if isinstance(improved_prompts_response, list)
            else [improved_prompts_response]
        )
        for response_item in responses:
            if not response_item:
                continue
            improved_chat_prompts: dict[str, chat_prompt.ChatPrompt] = {}
            for prompt_name, improved_prompt in response_item.items():
                raw_messages = [x.model_dump() for x in improved_prompt.messages]
                messages_as_dicts = [
                    message for message in raw_messages if _message_has_content(message)
                ]
                original = original_prompts[prompt_name]
                allowed_roles = (
                    context.extra_params.get("optimizable_roles")
                    if context.extra_params
                    else None
                )
                dropped = count_disallowed_role_updates(
                    original.get_messages(), messages_as_dicts, allowed_roles
                )
                if dropped:
                    logger.debug(
                        "HRPO candidate dropped %s update(s) for prompt '%s' due to optimize_prompt constraints.",
                        dropped,
                        prompt_name,
                    )
                messages_as_dicts = apply_role_constraints(
                    original.get_messages(), messages_as_dicts, allowed_roles
                )
                candidate_prompt = chat_prompt.ChatPrompt(
                    name=original.name,
                    messages=messages_as_dicts,
                    tools=best_prompts[prompt_name].tools,
                    function_map=best_prompts[prompt_name].function_map,
                    model=original.model,
                    model_parameters=original.model_kwargs,
                )
                candidate_prompt = self._apply_tool_updates_from_improvement(
                    candidate_prompt=candidate_prompt,
                    improved_prompt=improved_prompt,
                    context=context,
                )
                improved_chat_prompts[prompt_name] = candidate_prompt
            improved_chat_prompts_candidates.append(improved_chat_prompts)
        return improved_chat_prompts_candidates

    def _apply_tool_updates_from_improvement(
        self,
        *,
        candidate_prompt: chat_prompt.ChatPrompt,
        improved_prompt: ImprovedPrompt,
        context: OptimizationContext,
    ) -> chat_prompt.ChatPrompt:
        """Apply tool/parameter description updates from an ImprovedPrompt."""
        if not context.extra_params.get("optimize_tools"):
            return candidate_prompt
        if not (
            improved_prompt.tool_descriptions or improved_prompt.parameter_descriptions
        ):
            return candidate_prompt
        allowed_tools = set(context.extra_params.get("tool_names") or [])
        return segment_updates.apply_tool_updates_from_descriptions(
            prompt=candidate_prompt,
            tool_descriptions=improved_prompt.tool_descriptions,
            parameter_descriptions=improved_prompt.parameter_descriptions,
            allowed_tools=allowed_tools or None,
        )

    def _fallback_improvement(
        self,
        *,
        best_prompts: dict[str, chat_prompt.ChatPrompt],
        evaluation_dataset: opik.Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None,
        n_samples: int | float | str | None,
        context: OptimizationContext,
        best_score: float,
        round_handle: Any,
    ) -> tuple[dict[str, chat_prompt.ChatPrompt], float, EvaluationResult]:
        """Evaluate the current best prompt when no candidate improvements exist."""
        fallback_id = f"trial{context.trials_completed}_fallback"
        fallback_score, fallback_result = self._evaluate_prompts_with_result(
            prompts=best_prompts,
            dataset=evaluation_dataset,
            metric=metric,
            agent=agent,
            n_samples=n_samples,
            context=context,
            empty_score=best_score,
            sampling_tag=fallback_id,
        )
        runtime.record_and_post_trial(
            optimizer=self,
            context=context,
            prompt_or_payload=best_prompts,
            score=fallback_score,
            candidate_id=fallback_id,
            round_handle=round_handle,
            post_extras=None,
            post_metrics=None,
        )
        return best_prompts, fallback_score, fallback_result

    def _evaluate_improved_candidates(
        self,
        *,
        improved_chat_prompts_candidates: list[dict[str, chat_prompt.ChatPrompt]],
        evaluation_dataset: opik.Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None,
        n_samples: int | float | str | None,
        context: OptimizationContext,
        round_handle: Any,
    ) -> tuple[dict[str, chat_prompt.ChatPrompt], float, EvaluationResult]:
        """Evaluate improved candidates and return the best bundle."""
        best_candidate_id = f"trial{context.trials_completed}_best0"
        best_prompt_bundle = improved_chat_prompts_candidates[0]
        best_score_local, best_result = self._evaluate_prompts_with_result(
            prompts=best_prompt_bundle,
            dataset=evaluation_dataset,
            metric=metric,
            agent=agent,
            n_samples=n_samples,
            context=context,
            sampling_tag=best_candidate_id,
        )
        runtime.record_and_post_trial(
            optimizer=self,
            context=context,
            prompt_or_payload=best_prompt_bundle,
            score=best_score_local,
            candidate_id=best_candidate_id,
            round_handle=round_handle,
            post_extras=None,
            post_metrics=None,
        )

        # Evaluate remaining candidates and keep the best-scoring bundle.
        for idx, improved_chat_prompts in enumerate(
            improved_chat_prompts_candidates[1:], start=1
        ):
            candidate_id = f"trial{context.trials_completed}_cand{idx}"
            improved_score, improved_experiment_result = (
                self._evaluate_prompts_with_result(
                    prompts=improved_chat_prompts,
                    dataset=evaluation_dataset,
                    metric=metric,
                    agent=agent,
                    n_samples=n_samples,
                    context=context,
                    sampling_tag=candidate_id,
                )
            )
            runtime.record_and_post_trial(
                optimizer=self,
                context=context,
                prompt_or_payload=improved_chat_prompts,
                score=improved_score,
                candidate_id=candidate_id,
                round_handle=round_handle,
                post_extras=None,
                post_metrics=None,
            )
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
            "iterations_completed": self._iterations_completed,
            "convergence_threshold": self.convergence_threshold,
        }

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult:
        optimizable_prompts = context.prompts
        self.set_default_dataset_split(
            "validation" if context.validation_dataset is not None else "train"
        )
        dataset = context.dataset
        validation_dataset = context.validation_dataset
        metric = context.metric
        agent = context.agent
        n_samples = context.n_samples
        minibatch_samples = (
            context.n_samples_minibatch
            if context.n_samples_minibatch is not None
            else n_samples
        )
        max_trials = context.max_trials
        max_retries = context.extra_params.get("max_retries", 2)
        optimization = context.optimization

        initial_score = float(context.baseline_score or 0.0)
        best_score = initial_score
        best_prompts = optimizable_prompts

        iteration = 0
        previous_iteration_score: float = initial_score
        self._history_builder.clear()
        self._iterations_completed = iteration

        while context.trials_completed < max_trials:
            # Check should_stop flag at start of each iteration
            if self._should_stop_context(context):
                break

            iteration += 1
            self._iterations_completed = iteration
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
            round_handle = self.pre_round(context)

            sampling_tag = self._build_sampling_tag(
                scope="hro_root_cause",
                round_index=iteration,
            )
            hierarchical_analysis = iteration_ops.run_root_cause_analysis(
                optimizer=self,
                context=context,
                best_prompts=best_prompts,
                dataset=dataset,
                metric=metric,
                agent=agent,
                n_samples=minibatch_samples,
                sampling_tag=sampling_tag,
            )

            # Display synthesis results
            reporting.display_hierarchical_synthesis(
                total_test_cases=hierarchical_analysis.total_test_cases,
                num_batches=hierarchical_analysis.num_batches,
                synthesis_notes=hierarchical_analysis.synthesis_notes or "",
                verbose=self.verbose,
            )

            # Display identified failure modes
            reporting.display_failure_modes(
                hierarchical_analysis.unified_failure_modes, verbose=self.verbose
            )

            best_prompts, best_score = iteration_ops.improve_over_failure_modes(
                optimizer=self,
                context=context,
                hierarchical_analysis=hierarchical_analysis,
                optimizable_prompts=optimizable_prompts,
                best_prompts=best_prompts,
                best_score=best_score,
                dataset=dataset,
                validation_dataset=validation_dataset,
                metric=metric,
                agent=agent,
                n_samples=minibatch_samples,
                max_retries=max_retries,
                max_trials=max_trials,
                optimization_id=optimization.id if optimization else None,
                round_handle=round_handle,
            )

            # Check for convergence after iteration
            iteration_ops.finalize_iteration(
                optimizer=self,
                context=context,
                round_index=iteration,
                hierarchical_analysis=hierarchical_analysis,
                best_prompts=best_prompts,
                best_score=best_score,
                previous_iteration_score=previous_iteration_score,
                round_handle=round_handle,
            )

            # Update previous score for next iteration
            previous_iteration_score = best_score

        # finish_reason, stopped_early, stop_reason are handled by base class
        # Align with context in case trial accounting updated current_best_score.
        if context.current_best_score is not None and (
            best_score is None or context.current_best_score > best_score
        ):
            best_score = context.current_best_score
        history_entries = self.get_history_entries()
        for entry in history_entries:
            entry.setdefault("trials_completed", context.trials_completed)
        return AlgorithmResult(
            best_prompts=best_prompts,
            best_score=best_score,
            history=history_entries,
            metadata={
                # Algorithm-specific fields only (framework fields handled by base)
                "n_threads": self.n_threads,
                "max_parallel_batches": self.max_parallel_batches,
                "max_retries": max_retries,
                "convergence_threshold": self.convergence_threshold,
                "iterations_completed": iteration,
            },
        )
