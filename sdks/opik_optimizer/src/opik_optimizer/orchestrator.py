"""
OptimizationOrchestrator - Lifecycle manager for optimization runs.

This module provides the orchestration layer that manages the complete
optimization lifecycle, separating concerns from the optimizer algorithms.
"""

from __future__ import annotations

import logging
from typing import Any, TYPE_CHECKING

from . import reporting_utils
from .evaluator import Evaluator

if TYPE_CHECKING:
    from opik import Dataset
    from .api_objects import chat_prompt
    from .api_objects.types import MetricFunction
    from .agents import OptimizableAgent
    from .base_optimizer import BaseOptimizer, OptimizationContext
    from .optimization_result import OptimizationResult

logger = logging.getLogger(__name__)


class OptimizationOrchestrator:
    """
    Lifecycle manager for optimization runs.

    The orchestrator handles:
    - Pre-processing: validation, setup, baseline computation
    - Delegation to optimizer's run_optimization()
    - Post-processing: result building, display, finalization

    The orchestrator does NOT:
    - Own the optimization loop (that's the optimizer's job)
    - Handle early stop logic (that's in BaseOptimizer.evaluate())
    """

    def __init__(
        self,
        optimizer: "BaseOptimizer",
        perfect_score: float = 0.95,
        skip_perfect_score: bool = True,
        verbose: int = 1,
    ) -> None:
        """
        Initialize the orchestrator.

        Args:
            optimizer: The optimizer instance to orchestrate.
            perfect_score: Score threshold for early stopping.
            skip_perfect_score: Whether to skip optimization if baseline meets threshold.
            verbose: Verbosity level for display.
        """
        self.optimizer = optimizer
        self.perfect_score = perfect_score
        self.skip_perfect_score = skip_perfect_score
        self.verbose = verbose

    def run(
        self,
        context: "OptimizationContext",
    ) -> "OptimizationResult":
        """
        Run the full optimization process.

        This method orchestrates the complete optimization lifecycle:
        1. Display header and configuration
        2. Create evaluator and set up context
        3. Compute baseline score
        4. Check for early stop at baseline
        5. Run optimizer's algorithm
        6. Display and return result

        Args:
            context: The optimization context with all setup completed.

        Returns:
            OptimizationResult with the optimized prompt and metrics.
        """
        optimizer = self.optimizer

        # ─────────────────────────────────────────────────────────────────
        # SETUP: Display and create evaluator
        # ─────────────────────────────────────────────────────────────────

        # Display header
        self._display_header(context.optimization_id)

        # Determine display prompt format
        display_prompt = (
            list(context.prompts.values())[0]
            if context.is_single_prompt_optimization
            else context.prompts
        )
        self._display_configuration(display_prompt, context)

        # Create evaluator and attach to context
        evaluator = Evaluator(
            dataset=context.evaluation_dataset,
            metric=context.metric,
            agent=context.agent,
            n_threads=getattr(optimizer, "n_threads", 12),
            n_samples=context.n_samples,
            project_name=context.project_name,
            optimization_id=context.optimization_id,
            seed=getattr(optimizer, "seed", None),
        )
        evaluator.set_optimizer_ref(optimizer)

        # Store evaluator on context for optimizer access
        context.evaluator = evaluator  # type: ignore[attr-defined]

        # Set optimizer's context reference
        optimizer._context = context  # type: ignore[attr-defined]

        # Allow subclasses to perform pre-optimization setup
        optimizer.pre_optimization(context)

        # ─────────────────────────────────────────────────────────────────
        # BASELINE: Compute and check for early stop
        # ─────────────────────────────────────────────────────────────────

        # Initialize context tracking fields
        context.trials_completed = 0  # type: ignore[attr-defined]
        context.should_stop = False  # type: ignore[attr-defined]
        context.finish_reason = None  # type: ignore[attr-defined]
        context.current_best_score = None  # type: ignore[attr-defined]
        context.current_best_prompt = None  # type: ignore[attr-defined]

        # Compute baseline using optimizer's evaluate method
        baseline_score = self._compute_baseline(context)
        context.baseline_score = baseline_score

        # Check if baseline triggered early stop
        if context.should_stop:  # type: ignore[attr-defined]
            return self._build_early_stop_result(context, baseline_score)

        # ─────────────────────────────────────────────────────────────────
        # OPTIMIZATION: Run the optimizer's algorithm
        # ─────────────────────────────────────────────────────────────────

        try:
            result = optimizer.run_optimization(context)

            # Ensure finish_reason is set
            if context.finish_reason is None:  # type: ignore[attr-defined]
                context.finish_reason = "completed"  # type: ignore[attr-defined]

            # Post-optimization hook
            result = optimizer.post_optimization(context, result)

            # ─────────────────────────────────────────────────────────────
            # FINALIZATION: Display and return result
            # ─────────────────────────────────────────────────────────────

            self._display_final_result(context, result)
            self._finalize_optimization(context, status="completed")

            return result

        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            self._finalize_optimization(context, status="cancelled")
            raise

    def _compute_baseline(self, context: "OptimizationContext") -> float:
        """
        Compute baseline score with display.

        Args:
            context: The optimization context.

        Returns:
            The baseline score.
        """
        with reporting_utils.display_evaluation(verbose=self.verbose) as reporter:
            # Use optimizer's evaluate method which handles progress tracking
            baseline_score = self.optimizer.evaluate(
                context.prompts,
                experiment_config=self._prepare_experiment_config(context),
            )
            reporter.set_score(baseline_score)

        return baseline_score

    def _prepare_experiment_config(
        self, context: "OptimizationContext"
    ) -> dict[str, Any]:
        """Prepare experiment configuration for evaluation."""
        return self.optimizer._prepare_experiment_config(
            prompt=context.prompts,
            dataset=context.evaluation_dataset,
            metric=context.metric,
            agent=context.agent,
            experiment_config=context.experiment_config,
            is_single_prompt_optimization=context.is_single_prompt_optimization,
        )

    def _build_early_stop_result(
        self,
        context: "OptimizationContext",
        baseline_score: float,
    ) -> "OptimizationResult":
        """
        Build result when optimization stops early at baseline.

        Args:
            context: The optimization context.
            baseline_score: The baseline score achieved.

        Returns:
            OptimizationResult for early stop case.
        """
        from .optimization_result import OptimizationResult

        optimizer = self.optimizer

        # Select result prompts
        result_prompt, initial_prompt = optimizer._select_result_prompts(
            best_prompts=context.prompts,
            initial_prompts=context.initial_prompts,
            is_single_prompt_optimization=context.is_single_prompt_optimization,
        )

        # Display result
        self._display_final_result_simple(
            initial_score=baseline_score,
            best_score=baseline_score,
            prompt=result_prompt,
        )

        # Build details
        details = {
            "initial_score": baseline_score,
            "stopped_early": True,
            "finish_reason": context.finish_reason,  # type: ignore[attr-defined]
            "trials_completed": context.trials_completed,  # type: ignore[attr-defined]
            "perfect_score": self.perfect_score,
            "skip_perfect_score": self.skip_perfect_score,
            "model": optimizer.model,
        }

        # Merge optimizer-specific metadata
        optimizer_metadata = optimizer.get_metadata(context)
        details.update(optimizer_metadata)

        self._finalize_optimization(context, status="completed")

        return OptimizationResult(
            optimizer=optimizer.__class__.__name__,
            prompt=result_prompt,
            score=baseline_score,
            metric_name=context.metric.__name__,
            initial_prompt=initial_prompt,
            initial_score=baseline_score,
            details=details,
            history=[],
            llm_calls=optimizer.llm_call_counter,
            llm_calls_tools=optimizer.llm_calls_tools_counter,
            llm_cost_total=optimizer.llm_cost_total,
            llm_token_usage_total=optimizer.llm_token_usage_total,
            dataset_id=context.dataset.id,
            optimization_id=context.optimization_id,
        )

    def _display_header(self, optimization_id: str | None = None) -> None:
        """Display optimization header."""
        reporting_utils.display_header(
            algorithm=self.optimizer.__class__.__name__,
            optimization_id=optimization_id,
            verbose=self.verbose,
        )

    def _display_configuration(
        self,
        prompt: "chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]",
        context: "OptimizationContext",
    ) -> None:
        """Display optimization configuration."""
        reporting_utils.display_configuration(
            messages=prompt,
            optimizer_config=self.optimizer.get_config(context),
            verbose=self.verbose,
        )

    def _display_final_result(
        self,
        context: "OptimizationContext",
        result: "OptimizationResult",
    ) -> None:
        """Display final optimization result."""
        from .api_objects import chat_prompt as cp

        # Determine result prompt for display
        if isinstance(result.prompt, cp.ChatPrompt):
            result_prompt = result.prompt
        elif isinstance(result.prompt, dict):
            if context.is_single_prompt_optimization:
                result_prompt = list(result.prompt.values())[0]
            else:
                result_prompt = result.prompt
        else:
            result_prompt = result.prompt

        initial_score = (
            result.initial_score
            if result.initial_score is not None
            else context.baseline_score
        )

        reporting_utils.display_result(
            initial_score=initial_score,
            best_score=result.score,
            prompt=result_prompt,
            verbose=self.verbose,
        )

    def _display_final_result_simple(
        self,
        initial_score: float,
        best_score: float,
        prompt: Any,
    ) -> None:
        """Display final result with simple parameters."""
        reporting_utils.display_result(
            initial_score=initial_score,
            best_score=best_score,
            prompt=prompt,
            verbose=self.verbose,
        )

    def _finalize_optimization(
        self,
        context: "OptimizationContext",
        status: str = "completed",
    ) -> None:
        """Finalize the optimization run."""
        if context.optimization is not None:
            self.optimizer._update_optimization(context.optimization, status)
            logger.debug(
                f"Optimization {context.optimization_id} status updated to {status}."
            )
