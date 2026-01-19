from __future__ import annotations

from typing import Any, cast, overload, Literal
from collections.abc import Iterator
import copy
import inspect
import logging
import time
import json
from abc import ABC
import random
import math
import importlib.metadata

from contextlib import contextmanager

import litellm
from opik.rest_api.core import ApiError
from opik.api_objects import optimization
from opik import Dataset, opik_context
from opik.evaluation.evaluation_result import EvaluationResult

from .core import evaluation as task_evaluator
from . import helpers
from .utils.display.run import OptimizationRunDisplay, RunDisplay
from .api_objects import chat_prompt
from .api_objects.types import MetricFunction
from .agents import LiteLLMAgent, OptimizableAgent
from .constants import (
    resolve_project_name,
    normalize_eval_threads,
)
from .core.results import (
    OptimizationHistoryState,
    OptimizationResult,
    OptimizationRound,
    build_candidate_entry,
)
from .core.state import AlgorithmResult, OptimizationContext
from .utils.logging import debug_log
from .utils.prompt_library import PromptLibrary, PromptOverrides
from .utils.candidate_selection import select_candidate

# Don't use unsupported params:
litellm.drop_params = True

# Set up logging:
logger = logging.getLogger(__name__)


try:
    _OPTIMIZER_VERSION = importlib.metadata.version("opik_optimizer")
except importlib.metadata.PackageNotFoundError:  # pragma: no cover - dev installs
    _OPTIMIZER_VERSION = "unknown"


# Valid reasons for optimization to finish
class BaseOptimizer(ABC):
    # Subclasses define their prompts here
    DEFAULT_PROMPTS: dict[str, str] = {}

    def __init__(
        self,
        model: str,
        verbose: int = 1,
        seed: int = 42,
        model_parameters: dict[str, Any] | None = None,
        reasoning_model: str | None = None,
        reasoning_model_parameters: dict[str, Any] | None = None,
        name: str | None = None,
        skip_perfect_score: bool = True,
        perfect_score: float = 0.95,
        prompt_overrides: PromptOverrides = None,
        display: RunDisplay | None = None,
    ) -> None:
        """
        Base class for optimizers.

        Args:
           model: LiteLLM model name for optimizer's internal reasoning/generation calls
           verbose: Controls internal logging/progress bars (0=off, 1=on)
           seed: Random seed for reproducibility
           model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
               Common params: temperature, max_tokens, max_completion_tokens, top_p,
               presence_penalty, frequency_penalty.
               See: https://docs.litellm.ai/docs/completion/input
               Note: These params control the optimizer's default reasoning model, NOT the prompt evaluation.
           reasoning_model: Optional override for the optimizer's reasoning/analysis model. Falls back to ``model``.
           reasoning_model_parameters: Optional LiteLLM params for the reasoning model. Falls back to ``model_parameters``.
           name: Optional name for the optimizer instance. This will be used when creating optimizations.
           skip_perfect_score: Whether to short-circuit optimization when baseline is strong.
           perfect_score: Score threshold treated as "good enough" for short-circuiting.
           prompt_overrides: Optional dict or callable to customize internal prompts.
               Dict: {"prompt_key": "new_template"} to override specific prompts.
               Callable: function(prompts: PromptLibrary) -> None to modify prompts programmatically.
           display: Optional display handler for run output (used for testing or alternate renderers).
        """
        self.model = model
        self.model_parameters = model_parameters or {}
        self.reasoning_model = reasoning_model or model
        self.reasoning_model_parameters = (
            reasoning_model_parameters or self.model_parameters
        )
        self.verbose = verbose
        self.seed = seed
        self.name = name
        self.skip_perfect_score = skip_perfect_score
        self.perfect_score = perfect_score
        self.experiment_config = None
        self._opik_client = None  # Lazy initialization
        self.current_optimization_id: str | None = None  # Track current optimization
        self.project_name: str = resolve_project_name()
        self.n_threads: int = normalize_eval_threads(None)  # Safe default thread count

        # Counters, history and counters for usage.
        self._history_builder: OptimizationHistoryState = OptimizationHistoryState()
        self.llm_call_counter = 0
        self.llm_call_tools_counter = 0
        self.llm_cost_total: float = 0.0
        self.llm_token_usage_total: dict[str, int] = {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }
        self._trials_completed: int = 0
        self._rounds_completed: int = 0
        self._reporter: Any | None = None
        self._last_candidate_entry: dict[str, Any] | None = None
        self._display: RunDisplay = display or OptimizationRunDisplay(verbose=verbose)

        # Current optimization context (set during optimize_prompt, used by evaluate())
        self.__context: OptimizationContext | None = None

        # Initialize prompt library with overrides
        self._prompts = PromptLibrary(self.DEFAULT_PROMPTS, prompt_overrides)

    @property
    def _context(self) -> OptimizationContext:
        """
        Access the current optimization context.

        This property is only valid during an active optimization run.
        It returns the non-optional type since these methods are only
        called when optimization is in progress.
        """
        return self.__context  # type: ignore[return-value]

    @_context.setter
    def _context(self, value: OptimizationContext | None) -> None:
        self.__context = value

    @property
    def prompts(self) -> PromptLibrary:
        """Access the prompt library for this optimizer."""
        return self._prompts

    def _should_skip_optimization(
        self,
        baseline_score: float | None,
        *,
        skip_perfect_score: bool | None = None,
        perfect_score: float | None = None,
    ) -> bool:
        """Return True if the baseline score is already good enough."""
        if baseline_score is None:
            return False
        effective_skip = (
            self.skip_perfect_score
            if skip_perfect_score is None
            else skip_perfect_score
        )
        if not effective_skip:
            return False
        threshold = self.perfect_score if perfect_score is None else perfect_score
        return baseline_score >= threshold

    def _should_stop_context(self, context: OptimizationContext) -> bool:
        """
        Return True when the optimization should stop based on context flags/budget.

        Order of precedence:
        1) Explicit should_stop flag (set elsewhere, e.g., optimizer logic)
        2) Perfect score (only when skip_perfect_score is True)
        3) Max trials reached
        """
        if context.should_stop:
            return True

        if self.skip_perfect_score and context.current_best_score is not None:
            if context.current_best_score >= self.perfect_score:
                context.finish_reason = context.finish_reason or "perfect_score"
                context.should_stop = True
                debug_log(
                    "early_stop",
                    reason=context.finish_reason,
                    best_score=context.current_best_score,
                    trials_completed=context.trials_completed,
                )
                return True

        if context.trials_completed >= context.max_trials:
            context.finish_reason = context.finish_reason or "max_trials"
            context.should_stop = True
            debug_log(
                "early_stop",
                reason=context.finish_reason,
                trials_completed=context.trials_completed,
                max_trials=context.max_trials,
            )
            return True

        return False

    def _reset_counters(self) -> None:
        """Reset all call counters for a new optimization run."""
        self.llm_call_counter = 0
        self.llm_call_tools_counter = 0
        self.llm_cost_total = 0.0
        self.llm_token_usage_total = {
            "prompt_tokens": 0,
            "completion_tokens": 0,
            "total_tokens": 0,
        }

    def _increment_llm_counter(self) -> None:
        """Increment the LLM call counter."""
        self.llm_call_counter += 1

    def _increment_llm_call_tools_counter(self) -> None:
        """Increment the tool call counter."""
        self.llm_call_tools_counter += 1

    def _add_llm_cost(self, cost: float | None) -> None:
        """Accumulate cost across optimizer calls."""
        if cost is None:
            return
        self.llm_cost_total += float(cost)

    def _add_llm_usage(self, usage: dict[str, Any] | None) -> None:
        """Accumulate token usage across optimizer calls."""
        # TODO: Should we not use get_metadata or get_config instead
        # to get the token usage? Or we can centralize in the OptimizationResult along
        # with the counters and functions for incrementing?
        if not usage:
            return
        self.llm_token_usage_total["prompt_tokens"] += int(
            usage.get("prompt_tokens", 0)
        )
        self.llm_token_usage_total["completion_tokens"] += int(
            usage.get("completion_tokens", 0)
        )
        self.llm_token_usage_total["total_tokens"] += int(usage.get("total_tokens", 0))

    def get_prompt(self, key: str, **fmt: Any) -> str:
        """Get a prompt template, optionally formatted with kwargs.

        Args:
            key: The prompt key to retrieve
            **fmt: Optional format kwargs to apply to the template

        Returns:
            The prompt template, formatted if kwargs provided

        Raises:
            KeyError: If key is not a valid prompt key
        """
        return self._prompts.get(key, **fmt)

    def list_prompts(self) -> list[str]:
        """List available prompt keys.

        Returns:
            Sorted list of prompt keys
        """
        return self._prompts.keys()

    def get_default_prompt(self, key: str) -> str:
        """Get the original default prompt (before overrides).

        Args:
            key: The prompt key to retrieve

        Returns:
            The original default template

        Raises:
            KeyError: If key is not a valid prompt key
        """
        return self._prompts.get_default(key)

    def _attach_agent_owner(self, agent: Any) -> None:
        """Attach this optimizer to the agent so it can push counters/cost."""
        # TODO: Refactor _attach_agent_owner to keep this inside _llm_calls.py?
        try:
            setattr(agent, "_optimizer_owner", self)
        except Exception:
            pass

    def _normalize_prompt_input(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    ) -> tuple[dict[str, chat_prompt.ChatPrompt], bool]:
        """
        Normalize prompt input to dict format for internal processing.

        Args:
            prompt: Single ChatPrompt or dict of ChatPrompts

        Returns:
            Tuple of (normalized_prompts_dict, is_single_prompt_optimization)
        """
        # TODO: Remove this method in favour of auto-detection.
        if isinstance(prompt, chat_prompt.ChatPrompt):
            return {prompt.name: prompt}, True
        return prompt, False

    def _create_optimization_run(
        self,
        dataset: Dataset,
        metric: MetricFunction,
        optimization_id: str | None = None,
    ) -> optimization.Optimization | None:
        """
        Create an Opik optimization run with consistent error handling.

        Args:
            dataset: The dataset being used for optimization
            metric: The metric function being optimized
            optimization_id: Optional pre-specified optimization ID

        Returns:
            The created Optimization object, or None if creation failed
        """
        try:
            opik_optimization = self.opik_client.create_optimization(
                # TODO(opik_optimizer/#dataset-splits): support dict of datasets (train/val/test) once API allows.
                dataset_name=dataset.name,
                objective_name=metric.__name__,
                metadata=self._build_optimization_metadata(),
                name=self.name,
                optimization_id=optimization_id,
            )
            self.current_optimization_id = (
                opik_optimization.id if opik_optimization is not None else None
            )
            logger.debug(
                f"Created optimization with ID: {self.current_optimization_id}"
            )
            return opik_optimization
        except Exception as e:
            logger.warning(f"Opik server error: {e}. Continuing without Opik tracking.")
            self.current_optimization_id = None
            return None

    def _select_evaluation_dataset(
        self,
        dataset: Dataset,
        validation_dataset: Dataset | None,
        warn_unsupported: bool = False,
    ) -> Dataset:
        """
        Select which dataset to use for evaluation.

        Args:
            dataset: The training dataset
            validation_dataset: Optional validation dataset
            warn_unsupported: If True, log warning that validation_dataset is not supported

        Returns:
            The dataset to use for evaluation
        """
        if validation_dataset is not None:
            if warn_unsupported:
                logger.warning(
                    f"{self.__class__.__name__} currently does not support validation dataset. "
                    f"Using `dataset` (training) for now. Ignoring `validation_dataset` parameter."
                )
                return dataset
            return validation_dataset
        return dataset

    def _setup_optimization(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        n_samples: int | None = None,
        experiment_config: dict[str, Any] | None = None,
        validation_dataset: Dataset | None = None,
        project_name: str | None = None,
        optimization_id: str | None = None,
        max_trials: int = 10,
        **extra_params: Any,
    ) -> OptimizationContext:
        # Normalize prompt input
        optimizable_prompts, is_single_prompt_optimization = (
            self._normalize_prompt_input(prompt)
        )

        # Validate optimization inputs
        self._validate_optimization_inputs(
            optimizable_prompts, dataset, metric, support_content_parts=True
        )

        # Validate n_samples against dataset size
        total_items = len(dataset.get_items())
        if total_items == 0:
            raise ValueError("dataset is empty")
        if n_samples is not None and n_samples > total_items:
            logger.warning(
                f"Requested n_samples ({n_samples}) is larger than dataset size ({total_items}). Using full dataset."
            )
            n_samples = None

        # Resolve project name
        project_name = resolve_project_name(project_name)
        self.project_name = project_name

        # Create agent if not provided
        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)
        self._attach_agent_owner(agent)

        # Reset counters
        self._reset_counters()

        # Get existing optimization if provided
        if optimization_id:
            opik_optimization = self.opik_client.get_optimization_by_id(optimization_id)
            self.current_optimization_id = opik_optimization.id
            logger.debug(f"Using existing optimization with ID: {opik_optimization.id}")
        else:
            opik_optimization = self._create_optimization_run(
                dataset, metric, optimization_id
            )

        # Select evaluation dataset
        evaluation_dataset = self._select_evaluation_dataset(
            dataset, validation_dataset
        )

        # Add validation dataset to experiment config if provided
        if validation_dataset is not None:
            experiment_config = experiment_config or {}
            experiment_config["validation_dataset"] = getattr(
                validation_dataset, "name", validation_dataset.__class__.__name__
            )
            experiment_config["validation_dataset_id"] = getattr(
                validation_dataset, "id", None
            )

        # Create initial prompts
        initial_prompts = {name: p.copy() for name, p in optimizable_prompts.items()}

        # Return optimization context
        return OptimizationContext(
            prompts=optimizable_prompts,
            initial_prompts=initial_prompts,
            is_single_prompt_optimization=is_single_prompt_optimization,
            dataset=dataset,
            evaluation_dataset=evaluation_dataset,
            validation_dataset=validation_dataset,
            metric=metric,
            agent=agent,
            optimization=opik_optimization,
            optimization_id=self.current_optimization_id,
            experiment_config=experiment_config,
            n_samples=n_samples,
            max_trials=max_trials,
            project_name=project_name,
            allow_tool_use=bool(extra_params.get("allow_tool_use", True)),
            baseline_score=None,
            extra_params=extra_params,
        )

    def _calculate_baseline(self, context: OptimizationContext) -> float:
        """
        Calculate and display the baseline score for the initial prompt.

        This method evaluates the initial prompt and displays the result.
        Subclasses can override this to customize baseline computation or display.

        Args:
            context: The optimization context with prompts and configuration

        Returns:
            The baseline score
        """
        if not hasattr(self, "_display"):
            self._display = OptimizationRunDisplay(verbose=self.verbose)
        debug_log(
            "baseline_start",
            dataset=getattr(context.evaluation_dataset, "name", None),
            max_trials=context.max_trials,
            n_samples=context.n_samples,
            n_threads=getattr(self, "n_threads", None),
        )
        with self._display.baseline_evaluation(context) as baseline_reporter:
            baseline_score = self.evaluate_prompt(
                prompt=context.prompts,
                dataset=context.evaluation_dataset,
                metric=context.metric,
                agent=context.agent,
                n_samples=context.n_samples,
                n_threads=getattr(self, "n_threads", None),
                verbose=self.verbose,
                allow_tool_use=context.allow_tool_use,
                experiment_config=self._prepare_experiment_config(
                    prompt=context.prompts,
                    dataset=context.evaluation_dataset,
                    metric=context.metric,
                    agent=context.agent,
                    experiment_config=context.experiment_config,
                    is_single_prompt_optimization=context.is_single_prompt_optimization,
                ),
            )
            baseline_score = self._coerce_score(baseline_score)
            baseline_reporter.set_score(baseline_score)
            debug_log("baseline_end", score=baseline_score)

        return baseline_score

    def evaluate(
        self,
        prompts: dict[str, chat_prompt.ChatPrompt],
        experiment_config: dict[str, Any] | None = None,
    ) -> float:
        """
        Evaluate prompts and return the score.

        This is THE method optimizers should call to score candidates.
        It handles all framework concerns:
        - Progress tracking (trials_completed++)
        - Early stop checking (sets should_stop flag)
        - Best score/prompt tracking
        - Display progress (via _on_evaluation hook)

        After calling this method, optimizers should check context.should_stop
        to determine if they should exit their loop.

        Args:
            prompts: Dict of named prompts to evaluate (e.g., {"main": ChatPrompt(...)}).
                     Single-prompt optimizations use a dict with one entry.
            experiment_config: Optional experiment configuration.

        Returns:
            The score (float).

        Example:
            for candidate in candidates:
                score = self.evaluate(candidate)
                if self._context.should_stop:
                    break
        """
        context = self._context
        score = self.evaluate_prompt(
            prompt=prompts,
            dataset=context.evaluation_dataset,
            metric=context.metric,
            agent=context.agent,
            experiment_config=experiment_config,
            n_samples=context.n_samples,
            n_threads=normalize_eval_threads(getattr(self, "n_threads", None)),
            verbose=0,
            allow_tool_use=context.allow_tool_use,
        )
        # Record trial and update counters
        coerced_score = self._coerce_score(score)
        context.trials_completed += 1
        prev_best_score = context.current_best_score
        if prev_best_score is None or coerced_score > prev_best_score:
            context.current_best_score = coerced_score
            context.current_best_prompt = prompts

        # Call display hook
        self._on_evaluation(context, prompts, coerced_score, prev_best_score)
        debug_log(
            "evaluation_complete",
            trials_completed=context.trials_completed,
            score=coerced_score,
            best_score=context.current_best_score,
            dataset=getattr(context.evaluation_dataset, "name", None),
        )

        # Check early stop conditions - SET FLAG, don't raise
        self._should_stop_context(context)
        return coerced_score

    def _on_evaluation(
        self,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        score: float,
        prev_best_score: float | None = None,
    ) -> None:
        """Display progress after each evaluation."""
        if not hasattr(self, "_display"):
            self._display = OptimizationRunDisplay(verbose=self.verbose)
        self._display.evaluation_progress(
            context=context,
            prompts=prompts,
            score=self._coerce_score(score),
            display_info={"prev_best_score": prev_best_score},
        )

    def post_optimization(
        self,
        context: OptimizationContext,
        result: OptimizationResult,
    ) -> OptimizationResult:
        """
        Hook called after optimization completes.

        This is the public interface that the orchestrator calls.
        Subclasses can override to modify the result before returning.

        Args:
            context: The optimization context.
            result: The optimization result.

        Returns:
            The (possibly modified) optimization result.
        """
        return result

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult | OptimizationResult:
        """
        Run the optimization algorithm.

        This is the method optimizers override to implement their algorithm.
        The method should:
        1. Control the optimization loop (when/how to generate candidates)
        2. Generate candidate prompts using algorithm-specific logic
        3. Call self.evaluate(prompts) for each candidate
        4. Check context.should_stop after evaluations
        5. Return AlgorithmResult with best prompt and score

        The framework (BaseOptimizer.optimize_prompt) handles:
        - Setup: validation, context creation, baseline computation
        - Trial tracking: context.trials_completed++ happens in evaluate()
        - Progress display: _on_evaluation() hook handles all display
        - Early stop: evaluate() checks and sets context.should_stop
        - Result building: converts AlgorithmResult to OptimizationResult

        Args:
            context: The optimization context with prompts, dataset, metric, etc.

        Returns:
            AlgorithmResult (preferred) or OptimizationResult (legacy).
        """
        return self._run_optimization(context)

    def _select_result_prompts(self, **kwargs: Any) -> tuple[Any, Any]:
        """Return the result prompt(s) and initial prompt(s) for output."""
        best_prompts = kwargs["best_prompts"]
        initial_prompts = kwargs["initial_prompts"]
        # TODO: I think we can auto-detect this by checking if
        # best_prompts is a single prompt or a dictionary. and remove this parameter.
        # is_single_prompt_optimization is not really needed anymore.
        is_single_prompt_optimization = kwargs["is_single_prompt_optimization"]
        if is_single_prompt_optimization:
            return list(best_prompts.values())[0], list(initial_prompts.values())[0]
        return best_prompts, initial_prompts

    def _build_early_result(self, **kwargs: Any) -> OptimizationResult:
        """Build a baseline-only OptimizationResult when skipping optimization."""
        score = kwargs["score"]
        return OptimizationResult(
            optimizer=kwargs["optimizer_name"],
            prompt=kwargs["prompt"],
            score=score,
            metric_name=kwargs["metric_name"],
            initial_prompt=kwargs["initial_prompt"],
            initial_score=score,
            details=kwargs["details"],
            history=kwargs.get("history", []) or [],
            # TODO: Bean counter should be moved to OptimizationResult.counters.? to simplify.
            llm_calls=kwargs.get("llm_calls"),
            llm_calls_tools=kwargs.get("llm_calls_tools"),
            llm_cost_total=kwargs.get("llm_cost_total"),
            llm_token_usage_total=kwargs.get("llm_token_usage_total"),
            dataset_id=kwargs.get("dataset_id"),
            optimization_id=kwargs.get("optimization_id"),
        )

    def _build_final_result(
        self,
        algorithm_result: AlgorithmResult,
        context: OptimizationContext,
    ) -> OptimizationResult:
        """
        Convert AlgorithmResult to OptimizationResult.

        This method is called by optimize_prompt() after run_optimization()
        completes. It adds common framework fields (optimizer name, initial
        score, LLM counters, etc.) to the algorithm-specific result.

        Args:
            algorithm_result: The result returned by run_optimization()
            context: The optimization context

        Returns:
            Complete OptimizationResult ready for return
        """
        # Select appropriate prompt format (single vs dict)
        result_prompt, initial_prompt = self._select_result_prompts(
            best_prompts=algorithm_result.best_prompts,
            initial_prompts=context.initial_prompts,
            # TODO: Remove this parameter in favour of auto-detection.
            is_single_prompt_optimization=context.is_single_prompt_optimization,
        )

        # Get optimizer-specific metadata
        optimizer_metadata = self.get_metadata(context)

        # Compute stopped_early from finish_reason
        finish_reason = context.finish_reason or "completed"
        stopped_early = finish_reason != "completed"

        # Build details dict - framework fields + algorithm metadata
        details = {
            "initial_score": context.baseline_score,
            "model": self.model,
            "temperature": self.model_parameters.get("temperature"),
            "model_parameters": dict(self.model_parameters),
            "trials_completed": context.trials_completed,
            "finish_reason": finish_reason,
            "stopped_early": stopped_early,
            "stop_reason": finish_reason,
            "verbose": self.verbose,
        }

        # Merge in optimizer-specific metadata
        details.update(optimizer_metadata)

        # Merge in algorithm-specific metadata (can override optimizer metadata)
        details.update(algorithm_result.metadata)

        if algorithm_result.history:
            history_entries: list[dict[str, Any]] = []
            for entry in algorithm_result.history:
                if hasattr(entry, "to_dict"):
                    history_entries.append(entry.to_dict())
                elif isinstance(entry, dict):
                    history_entries.append(entry)
            if not history_entries:
                history_entries = self._history_builder.get_entries()
        else:
            history_entries = self._history_builder.get_entries()

        if not history_entries:
            # Ensure a minimal round/trial is recorded when optimizers skip history logging.
            fallback_round = self.begin_round()
            self.record_candidate_entry(
                prompt_or_payload=result_prompt,
                score=algorithm_result.best_score,
                id="fallback",
            )
            self.post_candidate(
                result_prompt,
                score=algorithm_result.best_score,
                round_handle=fallback_round,
            )
            self.post_round(
                fallback_round,
                best_score=algorithm_result.best_score,
                best_prompt=result_prompt,
                stop_reason=finish_reason,
            )
            history_entries = self._history_builder.get_entries()

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=result_prompt,
            score=algorithm_result.best_score,
            metric_name=context.metric.__name__,
            initial_prompt=initial_prompt,
            initial_score=context.baseline_score,
            details=details,
            history=history_entries,
            llm_calls=self.llm_call_counter,
            llm_calls_tools=self.llm_call_tools_counter,
            dataset_id=context.dataset.id,
            optimization_id=context.optimization_id,
        )

    def cleanup(self) -> None:
        """
        Clean up resources and perform memory management.
        Should be called when the optimizer is no longer needed.
        """
        # Reset counters
        self._reset_counters()

        # Clear history to free memory
        self._history_builder.clear()

        # Clear Opik client if it exists
        if self._opik_client is not None:
            # Note: Opik client doesn't have explicit cleanup, but we can clear the reference
            self._opik_client = None
        self._reporter = None

        logger.debug(f"Cleaned up resources for {self.__class__.__name__}")

    def _set_reporter(self, reporter: Any | None) -> None:
        """Set the active reporter for the current optimization scope."""
        self._reporter = reporter

    def _clear_reporter(self) -> None:
        """Clear the active reporter."""
        self._reporter = None

    def __del__(self) -> None:
        """Destructor to ensure cleanup is called."""
        try:
            self.cleanup()
        except Exception:
            # Ignore exceptions during cleanup in destructor
            pass

    @property
    def opik_client(self) -> Any:
        """Lazy initialization of Opik client."""
        if self._opik_client is None:
            import opik

            self._opik_client = opik.Opik()
        return self._opik_client

    def _validate_optimization_inputs(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        support_content_parts: bool = False,
    ) -> None:
        """
        Validate common optimization inputs.

        Args:
            prompt: The chat prompt to validate
            dataset: The dataset to validate
            metric: The metric function to validate

        Raises:
            ValueError: If any input is invalid
        """
        if isinstance(prompt, dict):
            for prompt_value in prompt.values():
                if not isinstance(prompt_value, chat_prompt.ChatPrompt):
                    raise ValueError("Prompt must be a ChatPrompt object")

            if prompt_value._has_content_parts() and not support_content_parts:
                raise ValueError(
                    "Prompt has content parts, which are not supported by this optimizer - You can use the Hierarchical Reflective Optimizer instead."
                )
        elif isinstance(prompt, chat_prompt.ChatPrompt):
            if prompt._has_content_parts() and not support_content_parts:
                raise ValueError(
                    "Prompt has content parts, which are not supported by this optimizer - You can use the Hierarchical Reflective Optimizer instead."
                )
        else:
            # FIXME: PossibleUnreachable code?
            raise ValueError(
                "Prompt must be a ChatPrompt object or a dictionary of ChatPrompt objects"
            )

        if not isinstance(dataset, Dataset):
            # FIXME: PossibleUnreachable code?
            raise ValueError("Dataset must be a Dataset object")

        if not callable(metric):
            # FIXME: PossibleUnreachable code?
            raise ValueError(
                "Metric must be a callable function that takes `dataset_item` and `llm_output` as arguments, "
                "and optionally `task_span` for metrics that need access to span information."
            )

    def _setup_agent_class(
        self, prompt: chat_prompt.ChatPrompt, agent_class: Any = None
    ) -> Any:
        """Resolve the agent class used for prompt evaluations.

        Ensures custom implementations inherit from :class:`OptimizableAgent` and that
        the optimizer reference is always available to track metrics.

        Args:
            prompt: The chat prompt driving the agent instance.
            agent_class: Optional custom agent class supplied by the caller.

        Returns:
            The agent class to instantiate for dataset evaluations.
        """
        if agent_class is None:
            return LiteLLMAgent
        if not issubclass(agent_class, OptimizableAgent):
            raise TypeError(
                f"agent_class must inherit from OptimizableAgent, got {agent_class.__name__}"
            )
        return agent_class

    def _bind_optimizer_to_agent(self, agent: OptimizableAgent) -> OptimizableAgent:
        """Attach this optimizer to the agent instance without mutating the class."""
        try:
            agent.optimizer = self  # type: ignore[attr-defined]
        except Exception:  # pragma: no cover - custom agents may forbid new attrs
            logger.debug(
                "Unable to record optimizer on agent instance of %s",
                agent.__class__.__name__,
            )
        return agent

    def _instantiate_agent(
        self,
        *args: Any,
        agent_class: type[OptimizableAgent] | None = None,
        **kwargs: Any,
    ) -> OptimizableAgent:
        """
        Instantiate the provided agent_class (or self.agent_class) and bind optimizer.
        """
        resolved_class = agent_class or getattr(self, "agent_class", None)
        if resolved_class is None:
            raise ValueError("agent_class must be provided before instantiation")
        agent = resolved_class(*args, **kwargs)
        return self._bind_optimizer_to_agent(agent)

    def _extract_tool_prompts(
        self, tools: list[dict[str, Any]] | None
    ) -> dict[str, str] | None:
        """
        Extract tool names and descriptions from tools list.

        Args:
            tools: List of tool definitions in OpenAI/LiteLLM format

        Returns:
            Dictionary mapping tool names to descriptions, or None if no tools
        """
        if not tools:
            return None

        return {
            (tool.get("function", {}).get("name") or f"tool_{idx}"): tool.get(
                "function", {}
            ).get("description", "")
            for idx, tool in enumerate(tools)
        }

    # ------------------------------------------------------------------
    # Experiment metadata helpers
    # FIXME: Refactor, move to helpers.py or utils.py away from base optimizer
    # These are metadata helpers for annotation of calls, could go into a separate module.
    # ------------------------------------------------------------------

    @staticmethod
    def _deep_merge_dicts(
        base: dict[str, Any], overrides: dict[str, Any]
    ) -> dict[str, Any]:
        result = copy.deepcopy(base)
        for key, value in overrides.items():
            if (
                key in result
                and isinstance(result[key], dict)
                and isinstance(value, dict)
            ):
                result[key] = BaseOptimizer._deep_merge_dicts(result[key], value)
            else:
                result[key] = value
        return result

    @staticmethod
    def _serialize_tools(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
        tools_obj = getattr(prompt, "tools", None)
        if not isinstance(tools_obj, list):
            return []

        try:
            return copy.deepcopy(cast(list[dict[str, Any]], tools_obj))
        except Exception:  # pragma: no cover - defensive
            serialized_tools: list[dict[str, Any]] = []
            for tool in tools_obj:
                if isinstance(tool, dict):
                    serialized_tools.append({k: v for k, v in tool.items() if k})
            return serialized_tools

    @staticmethod
    def _describe_annotation(annotation: Any) -> str | None:
        if annotation is inspect._empty:
            return None
        if isinstance(annotation, type):
            return annotation.__name__
        return str(annotation)

    def _summarize_tool_signatures(
        self, prompt: chat_prompt.ChatPrompt
    ) -> list[dict[str, Any]]:
        signatures: list[dict[str, Any]] = []
        for name, func in getattr(prompt, "function_map", {}).items():
            callable_obj = getattr(func, "__wrapped__", func)
            try:
                sig = inspect.signature(callable_obj)
            except (TypeError, ValueError):  # pragma: no cover - defensive
                signatures.append({"name": name, "signature": "unavailable"})
                continue

            params: list[dict[str, Any]] = []
            for parameter in sig.parameters.values():
                params.append(
                    helpers.drop_none(
                        {
                            "name": parameter.name,
                            "kind": parameter.kind.name,
                            "annotation": self._describe_annotation(
                                parameter.annotation
                            ),
                            "default": (
                                None
                                if parameter.default is inspect._empty
                                else parameter.default
                            ),
                        }
                    )
                )

            signatures.append(
                helpers.drop_none(
                    {
                        "name": name,
                        "parameters": params,
                        "docstring": inspect.getdoc(callable_obj),
                    }
                )
            )
        return signatures

    def _build_agent_config(self, prompt: chat_prompt.ChatPrompt) -> dict[str, Any]:
        agent_config: dict[str, Any] = dict(prompt.to_dict())
        agent_config["project_name"] = getattr(prompt, "project_name", None)
        agent_config["model"] = getattr(prompt, "model", None) or self.model
        agent_config["tools"] = self._serialize_tools(prompt)
        agent_config["optimizer"] = self.__class__.__name__
        return helpers.drop_none(agent_config)

    # FIXME: Should we not use get_metadata or get_config instead
    def get_optimizer_metadata(self) -> dict[str, Any]:
        """Override in subclasses to expose optimizer-specific parameters."""
        return {}

    # FIXME: Should we not use get_metadata or get_config instead
    def _build_optimizer_metadata(self) -> dict[str, Any]:
        metadata = {
            "name": self.__class__.__name__,
            "version": _OPTIMIZER_VERSION,
            "model": self.model,
            "model_parameters": self.model_parameters or None,
            "seed": getattr(self, "seed", None),
            "num_threads": getattr(self, "num_threads", None),
        }

        # n_threads is used by some optimizers instead of num_threads
        if metadata["num_threads"] is None and hasattr(self, "n_threads"):
            metadata["num_threads"] = getattr(self, "n_threads")

        if hasattr(self, "reasoning_model"):
            metadata["reasoning_model"] = getattr(self, "reasoning_model")

        extra_parameters = self.get_optimizer_metadata()
        if extra_parameters:
            metadata["parameters"] = extra_parameters

        return helpers.drop_none(metadata)

    def _build_optimization_metadata(
        self, agent_class: type[OptimizableAgent] | None = None
    ) -> dict[str, Any]:
        """
        Build metadata dictionary for optimization creation to be used when
        creating Opik optimizations.

        Args:
            agent_class: Optional agent class. If None, will try to get from self.agent_class.

        Returns:
            Dictionary with 'optimizer' and optionally 'agent_class' keys.
        """
        metadata: dict[str, Any] = {"optimizer": self.__class__.__name__}
        if self.name:
            metadata["name"] = self.name

        # Try to get agent_class name from parameter or instance
        agent_class_name: str | None = None
        if agent_class is not None:
            agent_class_name = getattr(agent_class, "__name__", None)
        elif hasattr(self, "agent_class") and self.agent_class is not None:
            agent_class_name = getattr(self.agent_class, "__name__", None)

        if agent_class_name:
            metadata["agent_class"] = agent_class_name

        return metadata

    def _prepare_experiment_config(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        validation_dataset: Dataset | None = None,
        experiment_config: dict[str, Any] | None = None,
        configuration_updates: dict[str, Any] | None = None,
        additional_metadata: dict[str, Any] | None = None,
        is_single_prompt_optimization: bool = False,
    ) -> dict[str, Any]:
        """
        Prepare experiment configuration with dataset tracking.

        Args:
            dataset_training: Training dataset (used for feedback/context)
            validation_dataset: Optional validation dataset (used for ranking)
        """
        project_name = self.project_name

        # Handle dict vs single prompt for agent_config
        prompt_messages: list[dict[str, Any]] | dict[str, list[dict[str, Any]]]
        prompt_name: str | None | dict[str, str | None]
        prompt_project_name: str | None | dict[str, str | None]

        if isinstance(prompt, dict):
            # For dict prompts, use the first prompt for agent_config
            first_prompt = next(iter(prompt.values()))
            agent_config = self._build_agent_config(first_prompt)
            tool_signatures = self._summarize_tool_signatures(first_prompt)

            # If this is single prompt optimization, log as single prompt not dict
            if is_single_prompt_optimization:
                prompt_messages = first_prompt.get_messages()
                prompt_name = getattr(first_prompt, "name", None)
                prompt_project_name = getattr(first_prompt, "project_name", None)
            else:
                prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
                prompt_messages = {k: p.get_messages() for k, p in prompt_dict.items()}
                prompt_name = {
                    k: getattr(p, "name", None) for k, p in prompt_dict.items()
                }
                prompt_project_name = {
                    k: getattr(p, "project_name", None) for k, p in prompt_dict.items()
                }

            tools = self._serialize_tools(first_prompt)
        else:
            agent_config = self._build_agent_config(prompt)
            tool_signatures = self._summarize_tool_signatures(prompt)
            prompt_messages = prompt.get_messages()
            prompt_name = getattr(prompt, "name", None)
            tools = self._serialize_tools(prompt)
            prompt_project_name = getattr(prompt, "project_name", None)

        base_config: dict[str, Any] = {
            "project_name": project_name,
            "agent_config": agent_config,
            "metric": metric.__name__,
            "dataset_training": dataset.name,
            "dataset_training_id": dataset.id,
            "optimizer": self.__class__.__name__,
            "optimizer_metadata": self._build_optimizer_metadata(),
            "tool_signatures": tool_signatures,
            "configuration": {
                "prompt": prompt_messages,
                "prompt_name": prompt_name,
                "tools": tools,
                "prompt_project_name": prompt_project_name,
            },
        }

        if agent is not None:
            base_config["agent"] = agent.__class__.__name__

        if configuration_updates:
            base_config["configuration"] = self._deep_merge_dicts(
                base_config["configuration"], configuration_updates
            )

        if additional_metadata:
            base_config = self._deep_merge_dicts(base_config, additional_metadata)

        if experiment_config:
            base_config = self._deep_merge_dicts(base_config, experiment_config)

        if validation_dataset:
            base_config["validation_dataset"] = validation_dataset.name
            base_config["validation_dataset_id"] = validation_dataset.id

        return helpers.drop_none(base_config)

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return optimizer-specific configuration for display.

        Subclasses can override this to provide their configuration
        parameters that will be displayed to the user.

        Args:
            context: The optimization context

        Returns:
            Dictionary of configuration parameters to display
        """
        return {}

    def _finalize_finish_reason(self, context: OptimizationContext) -> None:
        """
        Set finish_reason if not already set by early stop conditions.

        This method is called after run_optimization() returns but before
        building the final result. It ensures finish_reason is properly set
        based on whether max_trials was reached or the optimization completed
        normally.

        Subclasses can override this to add custom finish reasons (e.g.,
        EvolutionaryOptimizer adds "no_improvement" for stagnation detection).

        Args:
            context: The optimization context
        """
        if context.finish_reason is None:
            if context.trials_completed >= context.max_trials:
                context.finish_reason = "max_trials"
            else:
                context.finish_reason = "completed"

    def _run_optimization(
        self,
        context: OptimizationContext,
    ) -> OptimizationResult:
        """
        Execute the core optimization algorithm (deprecated, use run_optimization).

        This is kept for backward compatibility. Subclasses should override
        run_optimization() instead.
        """
        raise NotImplementedError(
            f"{self.__class__.__name__} must implement run_optimization()"
        )

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """
        Return optimizer-specific metadata for the optimization result.

        This method is called to collect additional metadata about the
        optimization run. Subclasses can override to provide optimizer-specific
        fields that will be included in OptimizationResult.details.

        The optimizer should NOT know or care whether this is called for:
        - Early stop scenarios
        - Successful completion
        - Error handling

        Returns:
            Dictionary of optimizer-specific metadata fields.
            Common fields to include:
            - trials_completed: int
            - rounds_completed: int (if applicable)
            - iterations_completed: int (if applicable)
            Plus any optimizer-specific fields (e.g., hall_of_fame_size)
        """
        return {}

    def pre_optimization(self, context: OptimizationContext) -> None:
        """
        Hook called before running the optimization algorithm.

        Subclasses can override to perform setup that needs the context,
        such as setting instance variables (e.g., self.agent = context.agent).

        NOTE: Subclasses should NOT perform any display/reporting here.
        All display is handled by the base class.

        Args:
            context: The optimization context
        """
        pass

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        project_name: str | None = None,
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        allow_tool_use: bool = True,
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        """
        Optimize a prompt using the template method pattern.

        This method provides the standard optimization flow:
        1. Setup optimization context via _setup_optimization()
        2. Check for early stop if baseline score meets threshold
        3. Run the algorithm-specific optimization via _run_optimization()
        4. Finalize and update status via _finalize_optimization()

        Subclasses can override this method for custom behavior, but most
        should only need to implement _run_optimization().

        Args:
           prompt: The prompt to optimize (single ChatPrompt or dict of prompts)
           dataset: Opik dataset (training set - used for feedback/context)
               TODO/FIXME: This parameter will be deprecated in favor of dataset_training.
               For now, it serves as the training dataset parameter.
           metric: A metric function with signature (dataset_item, llm_output) -> float
           agent: Optional agent for prompt execution (defaults to LiteLLMAgent)
           experiment_config: Optional configuration for the experiment
           n_samples: Number of samples to use for evaluation
           auto_continue: Whether to continue optimization automatically
           project_name: Opik project name for logging traces (defaults to OPIK_PROJECT_NAME env or "Optimization")
           optimization_id: Optional ID to use when creating the Opik optimization run
           validation_dataset: Optional validation dataset for ranking candidates
           max_trials: Maximum number of optimization trials
           allow_tool_use: Whether tools may be executed during evaluation (default True)
           **kwargs: Additional arguments passed to _setup_optimization and _run_optimization

        Returns:
            OptimizationResult with the optimized prompt and metrics
        """
        # Reset counters at the start of each optimization run
        # (allows reusing the same optimizer instance for multiple runs)
        self._trials_completed = 0
        self._rounds_completed = 0
        self._history_builder.clear()

        context = self._setup_optimization(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            agent=agent,
            n_samples=n_samples,
            experiment_config=experiment_config,
            validation_dataset=validation_dataset,
            project_name=project_name,
            optimization_id=optimization_id,
            max_trials=max_trials,
            auto_continue=auto_continue,
            allow_tool_use=allow_tool_use,
            **kwargs,
        )
        debug_log(
            "optimize_start",
            optimizer=self.__class__.__name__,
            dataset=getattr(dataset, "name", None),
            max_trials=max_trials,
            n_samples=n_samples,
            n_threads=getattr(self, "n_threads", None),
        )

        # Base class handles ALL display - optimizers should not call reporting
        if self._display is None:
            self._display = OptimizationRunDisplay(verbose=self.verbose)
        self._display.show_header(
            algorithm=self.__class__.__name__,
            optimization_id=context.optimization_id,
            dataset_id=context.dataset.id,
        )

        # Determine the prompt to display (single or dict)
        display_prompt = (
            list(context.prompts.values())[0]
            if context.is_single_prompt_optimization
            else context.prompts
        )
        self._display.show_configuration(
            prompt=display_prompt,
            optimizer_config=self.get_config(context),
        )

        # Allow subclasses to perform pre-optimization setup (e.g., set self.agent)
        # Subclasses should NOT do any display here
        self.pre_optimization(context)

        # Store context for use by evaluate() method
        self._context = context

        # Calculate baseline score with display (subclasses can override _calculate_baseline)
        baseline_score = self._calculate_baseline(context)

        # Update context with baseline score
        context.baseline_score = baseline_score

        # Check for early stop if baseline meets threshold
        if self._should_skip_optimization(baseline_score):
            context.finish_reason = "perfect_score"
            context.should_stop = True
            logger.info(
                "Baseline score %.4f >= %.4f; skipping optimization.",
                baseline_score,
                self.perfect_score,
            )

            early_result_prompt, early_initial_prompt = self._select_result_prompts(
                best_prompts=context.prompts,
                initial_prompts=context.initial_prompts,
                is_single_prompt_optimization=context.is_single_prompt_optimization,
            )

            # Display early stop result
            self._display.show_final_result(
                initial_score=baseline_score,
                best_score=baseline_score,
                prompt=early_result_prompt,
            )

            # Build early stop details - framework owns the "stopped_early" concept
            # Get optimizer-specific metadata first (optimizer doesn't know about early stop)
            optimizer_metadata = self.get_metadata(context)

            # Build framework-level early stop details
            early_stop_details = {
                "initial_score": baseline_score,
                "stopped_early": True,
                "stop_reason": "baseline_score_met_threshold",
                "stop_reason_details": {"best_score": baseline_score},
                "perfect_score": self.perfect_score,
                "skip_perfect_score": self.skip_perfect_score,
                "model": self.model,
                "temperature": self.model_parameters.get("temperature"),
            }

            # Merge in optimizer-specific metadata first
            early_stop_details.update(optimizer_metadata)
            early_stop_details.pop("rounds_completed", None)

            # Set trials based on what actually happened
            # In early stop, we completed the baseline evaluation, so at minimum:
            # - trials_completed defaults to 1 if not set or 0 (baseline evaluation)
            if early_stop_details.get("trials_completed", 0) == 0:
                early_stop_details["trials_completed"] = 1

            # Build a minimal history entry for baseline-only early stop
            self._history_builder.clear()
            baseline_round = self.begin_round()
            self.record_candidate_entry(
                prompt_or_payload=early_result_prompt,
                score=baseline_score,
                id="baseline",
            )
            self.post_candidate(
                early_result_prompt,
                score=baseline_score,
                round_handle=baseline_round,
            )
            stop_reason = early_stop_details.get("stop_reason")
            self.post_round(
                baseline_round,
                best_score=baseline_score,
                best_prompt=early_result_prompt,
                stop_reason=stop_reason if isinstance(stop_reason, str) else None,
            )

            self._finalize_optimization(context, status="completed")
            debug_log(
                "optimize_end",
                optimizer=self.__class__.__name__,
                best_score=baseline_score,
                trials_completed=context.trials_completed,
                stop_reason=context.finish_reason,
            )
            return self._build_early_result(
                optimizer_name=self.__class__.__name__,
                prompt=early_result_prompt,
                initial_prompt=early_initial_prompt,
                score=baseline_score,
                metric_name=context.metric.__name__,
                details=early_stop_details,
                history=self._history_builder.get_entries(),
                llm_calls=self.llm_call_counter,
                llm_calls_tools=self.llm_call_tools_counter,
                dataset_id=context.dataset.id,
                optimization_id=context.optimization_id,
            )

        try:
            raw_result = self.run_optimization(context)

            # Finalize finish_reason if not set by optimizer
            self._finalize_finish_reason(context)

            if not isinstance(raw_result, AlgorithmResult):
                raise TypeError(
                    "run_optimization must return AlgorithmResult (legacy OptimizationResult is no longer supported)"
                )
            result = self._build_final_result(raw_result, context)

            # Display result
            result_prompt, _ = self._select_result_prompts(
                best_prompts={k: v for k, v in [("prompt", result.prompt)]}
                if isinstance(result.prompt, chat_prompt.ChatPrompt)
                else result.prompt
                if isinstance(result.prompt, dict)
                else {"prompt": result.prompt},
                initial_prompts=context.initial_prompts,
                is_single_prompt_optimization=context.is_single_prompt_optimization,
            )
            self._display.show_final_result(
                initial_score=result.initial_score
                if result.initial_score is not None
                else baseline_score,
                best_score=result.score,
                prompt=result_prompt,
            )

            self._finalize_optimization(context, status="completed")
            debug_log(
                "optimize_end",
                optimizer=self.__class__.__name__,
                best_score=result.score,
                trials_completed=context.trials_completed,
                stop_reason=context.finish_reason,
            )
            debug_logger = logging.getLogger("opik_optimizer.debug")
            if debug_logger.isEnabledFor(logging.DEBUG):
                try:
                    details_text = json.dumps(
                        result.details, default=str, indent=2, sort_keys=True
                    )
                    history_text = json.dumps(
                        self._history_builder.get_entries(),
                        default=str,
                        indent=2,
                        sort_keys=True,
                    )
                    debug_logger.debug("final_state details=\n%s", details_text)
                    debug_logger.debug("final_state history=\n%s", history_text)
                except Exception:
                    debug_logger.debug(
                        "final_state details=%r history=%r",
                        result.details,
                        self._history_builder.get_entries(),
                    )
            return result
        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            self._finalize_optimization(context, status="cancelled")
            raise

    def get_history_entries(self) -> list[dict[str, Any]]:
        """
        Get normalized history entries captured by the builder.

        Optimizers should emit history through the builder; this accessor returns
        the normalized entries.
        """
        return self._history_builder.get_entries()

    def get_history_rounds(self) -> list[OptimizationRound]:
        """Get typed history rounds captured by the builder."""
        return self._history_builder.get_rounds()

    # --- History hook wrappers (candidate-first) ---
    def begin_round(self, **extras: Any) -> Any:
        """Start a history round and return its handle."""
        try:
            return self._history_builder.start_round(extras=extras or None)
        except AttributeError:
            # Fallback for legacy builders
            return None

    def set_default_dataset_split(self, dataset_split: str | None) -> None:
        """Set a default dataset split for subsequent trials/rounds."""
        try:
            self._history_builder.set_default_dataset_split(dataset_split)
        except AttributeError:
            return

    @contextmanager
    def with_dataset_split(self, dataset_split: str | None) -> Iterator[None]:
        """
        Temporarily set the default dataset split for history logging.
        Restores the previous split on exit.
        """
        try:
            ctx = self._history_builder.with_dataset_split(dataset_split)
        except AttributeError:
            # Manual fallback
            previous = getattr(self._history_builder, "_default_dataset_split", None)
            self.set_default_dataset_split(dataset_split)
            try:
                yield
            finally:
                self.set_default_dataset_split(previous)
            return

        with ctx:
            yield

    def set_selection_meta(self, selection_meta: dict[str, Any] | None) -> None:
        """Set selection metadata for the current round."""
        try:
            self._history_builder.set_selection_meta(selection_meta)
        except AttributeError:
            return

    def set_pareto_front(self, pareto_front: list[dict[str, Any]] | None) -> None:
        """Set pareto front for the current round."""
        try:
            self._history_builder.set_pareto_front(pareto_front)
        except AttributeError:
            return

    def record_candidate_entry(
        self,
        prompt_or_payload: Any,
        *,
        score: float | None = None,
        id: str | None = None,
        metrics: dict[str, Any] | None = None,
        notes: str | None = None,
        extra: dict[str, Any] | None = None,
    ) -> dict[str, Any]:
        """Normalize and cache a candidate entry for reuse in trial/round logging."""
        entry = build_candidate_entry(
            prompt_or_payload=prompt_or_payload,
            score=score,
            id=id,
            metrics=metrics,
            notes=notes,
            extra=extra,
        )
        self._last_candidate_entry = entry
        return entry

    def pre_candidate(self, candidate: Any, round_handle: Any) -> Any:
        """Optional pre-hook for candidate; returns the candidate as the handle."""
        return candidate

    def post_candidate(
        self,
        candidate_handle: Any,
        *,
        score: float | None,
        metrics: dict[str, Any] | None = None,
        extras: dict[str, Any] | None = None,
        candidates: list[dict[str, Any]] | None = None,
        dataset: str | None = None,
        dataset_split: str | None = None,
        trial_index: int | None = None,
        timestamp: str | None = None,
        round_handle: Any | None = None,
    ) -> None:
        """Record a candidate trial in history (post-candidate hook)."""
        if hasattr(self._history_builder, "record_trial"):
            stop_reason = None
            if self.__context is not None and self.__context.should_stop:
                stop_reason = getattr(self.__context, "finish_reason", None)
            candidate_list = candidates
            if candidate_list is None and self._last_candidate_entry is not None:
                candidate_list = [self._last_candidate_entry]
            self._history_builder.record_trial(
                round_handle=round_handle,
                score=score,
                candidate=candidate_handle,
                trial_index=trial_index,
                metrics=metrics,
                dataset=dataset,
                dataset_split=dataset_split,
                extras=extras,
                candidates=candidate_list,
                timestamp=timestamp,
                stop_reason=stop_reason,
            )

    def post_round(
        self,
        round_handle: Any,
        *,
        best_score: float | None = None,
        best_candidate: Any | None = None,
        best_prompt: Any | None = None,
        stop_reason: str | None = None,
        extras: dict[str, Any] | None = None,
        candidates: list[dict[str, Any]] | None = None,
        timestamp: str | None = None,
        dataset_split: str | None = None,
        pareto_front: list[dict[str, Any]] | None = None,
        selection_meta: dict[str, Any] | None = None,
    ) -> None:
        """Finalize a history round."""
        if hasattr(self._history_builder, "end_round"):
            self._history_builder.end_round(
                round_handle=round_handle,
                best_score=best_score,
                best_candidate=best_candidate,
                best_prompt=best_prompt,
                stop_reason=stop_reason,
                extras=extras,
                candidates=candidates,
                timestamp=timestamp,
                dataset_split=dataset_split,
                pareto_front=pareto_front,
                selection_meta=selection_meta,
            )

    def _update_optimization(
        self, optimization: optimization.Optimization, status: str
    ) -> None:
        # FIXME: remove when a solution is added to opik's optimization.update method
        count = 0
        while count < 3:
            try:
                optimization.update(status=status)
                break
            except ApiError:
                count += 1
                time.sleep(5)
        if count == 3:
            logger.warning("Unable to update optimization status; continuing...")

    @staticmethod
    def _coerce_score(raw_score: Any) -> float:
        """
        Normalize scores returned by metrics into builtin floats.

        Avoids comparison issues when metrics return Decimals, numpy scalars,
        or other numeric types (including inf).
        """
        try:
            score = float(raw_score)
        except (TypeError, ValueError):
            raise TypeError(
                f"Score must be convertible to float, got {type(raw_score).__name__}"
            )

        if math.isnan(score):
            raise ValueError("Score cannot be NaN.")

        return score

    def _finalize_optimization(
        self,
        context: OptimizationContext,
        status: str = "completed",
    ) -> None:
        if context.optimization is not None:
            self._update_optimization(context.optimization, status)
            logger.debug(
                f"Optimization {context.optimization_id} status updated to {status}."
            )
        # Clear context reference to allow garbage collection
        self._context = None

    @overload
    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        n_threads: int | None = None,
        verbose: int = 1,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        seed: int | None = None,
        return_evaluation_result: Literal[False] = False,
        allow_tool_use: bool | None = None,
    ) -> float: ...

    @overload
    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        n_threads: int | None = None,
        verbose: int = 1,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        seed: int | None = None,
        return_evaluation_result: Literal[True] = True,
        allow_tool_use: bool | None = None,
    ) -> EvaluationResult: ...

    def evaluate_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        n_threads: int | None = None,
        verbose: int = 1,
        dataset_item_ids: list[str] | None = None,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        seed: int | None = None,
        return_evaluation_result: bool = False,
        allow_tool_use: bool | None = None,
    ) -> float | EvaluationResult:
        random.seed(seed)
        n_threads = normalize_eval_threads(n_threads)
        if allow_tool_use is None:
            context = getattr(self, "_context", None)
            allow_tool_use = context.allow_tool_use if context is not None else True

        if agent is None:
            agent = LiteLLMAgent(project_name=self.project_name)

        def llm_task(dataset_item: dict[str, Any]) -> dict[str, Any]:
            # Let the agent push usage/cost counters back into this optimizer.
            self._attach_agent_owner(agent)
            # Wrap single prompt in dict for invoke_agent
            prompts_dict: dict[str, chat_prompt.ChatPrompt]
            if isinstance(prompt, dict):
                prompts_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
            else:
                prompts_dict = {prompt.name: prompt}

            # Only the active prompt's model_kwargs control pass@k for single-prompt runs.
            prompt_config = (
                list(prompts_dict.values())[0].model_kwargs
                if len(prompts_dict) == 1
                else {}
            )
            # Normalize n to an int so pass@k selection logic stays predictable.
            requested_n = int(prompt_config.get("n", 1) or 1)
            selection_policy = (
                prompt_config.get("selection_policy", "best_by_metric")
                if isinstance(prompt_config, dict)
                else "best_by_metric"
            )
            selection_policy = str(selection_policy or "best_by_metric").lower()

            if requested_n > 1 and hasattr(agent, "invoke_agent_candidates"):
                candidates = agent.invoke_agent_candidates(
                    prompts=prompts_dict,
                    dataset_item=dataset_item,
                    allow_tool_use=bool(allow_tool_use),
                )
                if not candidates:
                    raw_model_output = agent.invoke_agent(
                        prompts=prompts_dict,
                        dataset_item=dataset_item,
                        allow_tool_use=bool(allow_tool_use),
                    )
                    cleaned_model_output = raw_model_output.strip()
                else:
                    selection_result = select_candidate(
                        candidates=candidates,
                        policy=selection_policy,
                        metric=metric,
                        dataset_item=dataset_item,
                        candidate_logprobs=getattr(
                            agent, "_last_candidate_logprobs", None
                        ),
                        rng=random,
                    )
                    cleaned_model_output = selection_result.output.strip()
                    if logger.isEnabledFor(logging.DEBUG):
                        logger.debug(
                            "Pass@k selection: n=%s policy=%s candidates=%d chosen=%s scores=%s logprobs=%s",
                            requested_n,
                            selection_result.policy,
                            len(candidates),
                            selection_result.chosen_index,
                            selection_result.candidate_scores,
                            selection_result.candidate_logprobs,
                        )

                    try:
                        opik_context.update_current_trace(
                            metadata={
                                "opik_optimizer": {
                                    "selection_policy": selection_result.policy,
                                    "n_requested": requested_n,
                                    "candidates_scored": len(candidates),
                                    "candidate_scores": selection_result.candidate_scores,
                                    "candidate_logprobs": selection_result.candidate_logprobs,
                                    "chosen_index": selection_result.chosen_index,
                                }
                            }
                        )
                    except Exception:
                        pass
            else:
                raw_model_output = agent.invoke_agent(
                    prompts=prompts_dict,
                    dataset_item=dataset_item,
                    allow_tool_use=bool(allow_tool_use),
                )
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

        experiment_config = self._prepare_experiment_config(
            prompt=prompt,
            agent=agent,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
        )

        if n_samples is not None:
            if dataset_item_ids is not None:
                raise Exception("Can't use n_samples and dataset_item_ids")

            all_ids = [dataset_item["id"] for dataset_item in dataset.get_items()]
            n_samples = min(n_samples, len(all_ids))
            dataset_item_ids = random.sample(all_ids, n_samples)

        result = task_evaluator.evaluate(
            dataset=dataset,
            evaluated_task=llm_task,
            metric=metric,
            num_threads=n_threads,
            dataset_item_ids=dataset_item_ids,
            project_name=self.project_name,
            experiment_config=experiment_config,
            optimization_id=self.current_optimization_id,
            verbose=verbose,
            return_evaluation_result=return_evaluation_result,  # type: ignore[call-overload]
        )
        return result
