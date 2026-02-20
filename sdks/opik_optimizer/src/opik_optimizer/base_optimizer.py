from __future__ import annotations

from typing import Any, cast, overload, Literal
from collections.abc import Iterator
import logging
import time
from abc import ABC
import random
import importlib.metadata

from contextlib import contextmanager, nullcontext

import litellm
from opik.rest_api.core import ApiError
from opik.api_objects import optimization
from opik import Dataset, opik_context
from opik.evaluation.evaluation_result import (
    EvaluationResult,
    EvaluationResultOnDictItems,
)

from .core import evaluation as task_evaluator
from .core import runtime
from .utils.display.run import OptimizationRunDisplay, RunDisplay
from .utils.reporting import convert_tqdm_to_rich
from .api_objects import chat_prompt
from .api_objects.types import MetricFunction
from .agents import LiteLLMAgent, OptimizableAgent
from .constants import (
    OPTIMIZER_SHORT_NAMES,
    resolve_project_name,
    normalize_eval_threads,
)
from . import constants
from .core.results import (
    OptimizationHistoryState,
    OptimizationResult,
    OptimizationRound,
    build_candidate_entry,
)
from .core.state import (
    AlgorithmResult,
    OptimizationContext,
    build_optimization_metadata,
    prepare_experiment_config,
)
from .utils.logging import debug_log
from .utils.prompt_library import PromptLibrary, PromptOverrides
from .utils.candidate_selection import select_candidate
from .utils import rng as rng_utils
from .utils import sampling
from .utils import prompt_tracing
from .utils.prompt_roles import normalize_optimizable_roles

# Don't use unsupported params:
litellm.drop_params = True

# Set up logging:
logger = logging.getLogger(__name__)

try:
    _OPTIMIZER_VERSION = importlib.metadata.version("opik_optimizer")
except importlib.metadata.PackageNotFoundError:  # pragma: no cover - dev installs
    _OPTIMIZER_VERSION = "unknown"


class BaseOptimizer(ABC):
    # Subclasses define their prompts here
    DEFAULT_PROMPTS: dict[str, str] = {}
    # ------------------------------------------------------------------
    # Input validation & setup
    # ------------------------------------------------------------------

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
        self._rng = rng_utils.make_rng(seed)

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
        self._reporter: Any | None = None
        self._display: RunDisplay = display or OptimizationRunDisplay(verbose=verbose)
        self._last_evaluation_report: dict[str, Any] | None = None

        # Initialize prompt library with overrides
        self._prompts = PromptLibrary(self.DEFAULT_PROMPTS, prompt_overrides)

    @property
    def prompts(self) -> PromptLibrary:
        """Access the prompt library for this optimizer."""
        return self._prompts

    def _get_optimizer_short_name(self) -> str:
        """Get the short name for this optimizer class for tagging."""
        class_name = self.__class__.__name__
        return OPTIMIZER_SHORT_NAMES.get(class_name, class_name)

    def _tag_trace(
        self, phase: str | None = "Evaluation", extra_tags: list[str] | None = None
    ) -> None:
        """Best-effort trace tagging for optimizer evaluation."""
        if not self.current_optimization_id:
            return
        tags = [self._get_optimizer_short_name(), self.current_optimization_id]
        if phase:
            tags.append(phase)
        if extra_tags:
            tags.extend(extra_tags)
        try:
            opik_context.update_current_trace(tags=tags)
        except Exception:
            pass

    def _update_trace_metadata(self, metadata: dict[str, Any]) -> None:
        """Best-effort trace metadata update."""
        try:
            opik_context.update_current_trace(metadata=metadata)
        except Exception:
            pass

    # ------------------------------------------------------------------
    # Stopping & early-stop logic
    # ------------------------------------------------------------------

    def _should_skip_optimization(
        self,
        baseline_score: float | None,
        *,
        skip_perfect_score: bool | None = None,
        perfect_score: float | None = None,
    ) -> bool:
        """Return True if the baseline score is already good enough."""
        return runtime.should_skip_optimization(
            optimizer=self,
            baseline_score=baseline_score,
            skip_perfect_score=skip_perfect_score,
            perfect_score=perfect_score,
        )

    def _should_stop_context(self, context: OptimizationContext) -> bool:
        """
        Return True when the optimization should stop based on context flags/budget.

        Order of precedence:
        1) Explicit should_stop flag (set elsewhere, e.g., optimizer logic)
        2) Perfect score (only when skip_perfect_score is True)
        3) Max trials reached
        """
        return runtime.should_stop(self, context)

    # ------------------------------------------------------------------
    # Usage tracking
    # ------------------------------------------------------------------

    def _reset_counters(self) -> None:
        """Reset all call counters for a new optimization run."""
        runtime.reset_usage(self)
        self._last_evaluation_report = None

    def _increment_llm_counter(self) -> None:
        """Increment the LLM call counter."""
        runtime.increment_llm_call(self)

    def _increment_llm_call_tools_counter(self) -> None:
        """Increment the tool call counter."""
        runtime.increment_llm_tool_call(self)

    def _add_llm_cost(self, cost: float | None) -> None:
        """Accumulate cost across optimizer calls."""
        runtime.add_llm_cost(self, cost)

    def _add_llm_usage(self, usage: dict[str, Any] | None) -> None:
        """Accumulate token usage across optimizer calls."""
        runtime.add_llm_usage(self, usage)

    # ------------------------------------------------------------------
    # Prompt library
    # ------------------------------------------------------------------

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

    # ------------------------------------------------------------------
    # Metadata plumbing (default implementation)
    # ------------------------------------------------------------------

    def get_metadata(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer metadata for reporting and experiment tracking."""
        return {}

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer configuration for logging and experiment metadata."""
        return {}

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
        # TODO: Refactor _attach_agent_owner to keep this inside core/llm_calls.py?
        try:
            setattr(agent, "_optimizer_owner", self)
            setattr(agent, "optimizer", self)
            if getattr(agent, "trace_phase", None) is None:
                setattr(agent, "trace_phase", "Prompt Optimization")
        except Exception:
            pass

    def _set_agent_trace_phase(self, agent: OptimizableAgent, phase: str) -> None:
        """Best-effort setter for agent trace tagging phase."""
        try:
            agent.trace_phase = phase  # type: ignore[attr-defined]
        except Exception:  # pragma: no cover - defensive for custom agents
            logger.debug(
                "Unable to set trace_phase on agent instance of %s",
                agent.__class__.__name__,
            )

    # ------------------------------------------------------------------
    # Input normalization & validation
    # ------------------------------------------------------------------

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
        return prompt_tracing.normalize_prompt_input(prompt)

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
                metadata=build_optimization_metadata(self),
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
        n_samples: int | float | str | None = None,
        n_samples_minibatch: int | None = None,
        n_samples_strategy: str | None = None,
        experiment_config: dict[str, Any] | None = None,
        validation_dataset: Dataset | None = None,
        project_name: str | None = None,
        optimization_id: str | None = None,
        max_trials: int = 10,
        optimize_prompt: bool | str | list[str] | None = "system",
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

        # Select evaluation dataset early so we validate n_samples against the right split
        evaluation_dataset = self._select_evaluation_dataset(
            dataset, validation_dataset
        )

        self._validate_metric_requirements(evaluation_dataset, metric)

        # Validate n_samples against evaluation dataset size
        total_items = len(evaluation_dataset.get_items())
        if total_items == 0:
            raise ValueError("dataset is empty")
        if isinstance(n_samples, int) and n_samples > total_items:
            logger.warning(
                "Requested n_samples (%s) is larger than evaluation dataset size (%s). "
                "Using full evaluation dataset.",
                n_samples,
                total_items,
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

        # Default dataset split for history/logging (overridden by optimizers as needed)
        dataset_split = "validation" if validation_dataset is not None else "train"

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

        resolved_strategy = n_samples_strategy or getattr(
            self, "n_samples_strategy", None
        )
        if not isinstance(resolved_strategy, str) or not resolved_strategy:
            resolved_strategy = constants.DEFAULT_N_SAMPLES_STRATEGY

        optimizable_roles = normalize_optimizable_roles(optimize_prompt)
        extra_params["optimizable_roles"] = optimizable_roles
        self._optimizable_roles = optimizable_roles
        if not optimizable_roles:
            logger.warning(
                "optimize_prompt resolved to no roles; prompt content will not be mutated."
            )

        if hasattr(self, "allow_user_prompt_optimization"):
            setattr(self, "allow_user_prompt_optimization", "user" in optimizable_roles)

        # Return optimization context
        context = OptimizationContext(
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
            n_samples_minibatch=n_samples_minibatch,
            n_samples_strategy=resolved_strategy,
            max_trials=max_trials,
            project_name=project_name,
            allow_tool_use=bool(extra_params.get("allow_tool_use", True)),
            baseline_score=None,
            extra_params=extra_params,
            dataset_split=dataset_split,
        )

        # Keep history default split aligned with evaluation dataset.
        self.set_default_dataset_split(dataset_split)
        self.n_samples_strategy = resolved_strategy
        return context

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

        self.pre_baseline(context)

        def _evaluate_baseline() -> float:
            baseline_score = self.evaluate_prompt(
                prompt=context.prompts,
                dataset=context.evaluation_dataset,
                metric=context.metric,
                agent=context.agent,
                n_samples=context.n_samples,
                n_threads=getattr(self, "n_threads", None),
                verbose=self.verbose,
                allow_tool_use=context.allow_tool_use,
                experiment_config=prepare_experiment_config(
                    optimizer=self,
                    prompt=context.prompts,
                    dataset=context.evaluation_dataset,
                    metric=context.metric,
                    agent=context.agent,
                    experiment_config=context.experiment_config,
                    is_single_prompt_optimization=context.is_single_prompt_optimization,
                    configuration_updates=None,
                    additional_metadata=None,
                    validation_dataset=context.validation_dataset,
                    build_optimizer_version=_OPTIMIZER_VERSION,
                ),
            )
            coerced_score = self._coerce_score(baseline_score)
            self.post_baseline(context, coerced_score)
            return coerced_score

        return runtime.run_baseline_evaluation(
            optimizer=self,
            context=context,
            evaluate_fn=_evaluate_baseline,
        )

    # ------------------------------------------------------------------
    # Evaluation orchestration
    # ------------------------------------------------------------------

    def evaluate(
        self,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        experiment_config: dict[str, Any] | None = None,
        sampling_tag: str | None = None,
    ) -> float:
        """
        Evaluate prompts and return the score.

        This is THE method optimizers should call to score candidates.
        It handles all framework concerns:
        - Progress tracking (trials_completed++)
        - Early stop checking (sets should_stop flag)
        - Best score/prompt tracking
        - Display progress (via on_trial hook)

        After calling this method, optimizers should check context.should_stop
        to determine if they should exit their loop.

        Args:
            context: Optimization context for this run.
            prompts: Dict of named prompts to evaluate (e.g., {"main": ChatPrompt(...)}).
                     Single-prompt optimizations use a dict with one entry.
            experiment_config: Optional experiment configuration.
            sampling_tag: Optional sampling tag for deterministic subsampling per candidate.

        Returns:
            The score (float).

        Example:
            for candidate in candidates:
                score = self.evaluate(context, candidate)
                if context.should_stop:
                    break
        """
        prompt_tracing.record_candidate_prompts(prompts)
        self.pre_trial(context, prompts)
        try:
            suppress_progress = bool(
                (context.extra_params or {}).get("suppress_evaluation_progress", False)
            )
            progress_ctx = (
                nullcontext()
                if suppress_progress
                else convert_tqdm_to_rich("  Evaluation", verbose=self.verbose)
            )
            with progress_ctx:
                score = self.evaluate_prompt(
                    prompt=prompts,
                    dataset=context.evaluation_dataset,
                    metric=context.metric,
                    agent=context.agent,
                    experiment_config=experiment_config,
                    n_samples=context.n_samples,
                    n_samples_strategy=context.n_samples_strategy,
                    n_threads=normalize_eval_threads(getattr(self, "n_threads", None)),
                    verbose=self.verbose,
                    allow_tool_use=context.allow_tool_use,
                    sampling_tag=sampling_tag,
                )
        except Exception:
            context.finish_reason = "error"
            context.should_stop = True
            logger.exception("Evaluation failed; stopping optimization.")
            raise
        coerced_score = self._coerce_score(score)
        prev_best_score = context.current_best_score
        self.on_trial(context, prompts, coerced_score, prev_best_score)

        # Check early stop conditions - SET FLAG, don't raise
        self._should_stop_context(context)
        return coerced_score

    def _score_from_evaluation_result(
        self,
        evaluation_result: EvaluationResult | EvaluationResultOnDictItems,
        *,
        metric_name: str,
        empty_score: float | None = None,
    ) -> float:
        if not evaluation_result.test_results:
            return empty_score if empty_score is not None else 0.0
        objective_scores = task_evaluator._extract_objective_scores(
            evaluation_result, metric_name
        )
        if not objective_scores:
            return empty_score if empty_score is not None else 0.0
        return task_evaluator._average_finite_scores(
            objective_scores, objective_metric_name=metric_name
        )

    def evaluate_with_result(
        self,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        experiment_config: dict[str, Any] | None = None,
        *,
        empty_score: float | None = None,
        n_samples: int | float | str | None = None,
        n_samples_strategy: str | None = None,
        sampling_tag: str | None = None,
    ) -> tuple[float, EvaluationResult | EvaluationResultOnDictItems]:
        """Evaluate prompts and return both the score and EvaluationResult."""
        prompt_tracing.record_candidate_prompts(prompts)
        self.pre_trial(context, prompts)
        try:
            suppress_progress = bool(
                (context.extra_params or {}).get("suppress_evaluation_progress", False)
            )
            progress_ctx = (
                nullcontext()
                if suppress_progress
                else convert_tqdm_to_rich("  Evaluation", verbose=self.verbose)
            )
            with progress_ctx:
                evaluation_result = self.evaluate_prompt(
                    prompt=prompts,
                    dataset=context.evaluation_dataset,
                    metric=context.metric,
                    agent=context.agent,
                    experiment_config=experiment_config,
                    n_samples=context.n_samples if n_samples is None else n_samples,
                    n_samples_strategy=n_samples_strategy or context.n_samples_strategy,
                    n_threads=normalize_eval_threads(getattr(self, "n_threads", None)),
                    verbose=self.verbose,
                    allow_tool_use=context.allow_tool_use,
                    sampling_tag=sampling_tag,
                    return_evaluation_result=True,
                )
        except Exception:
            context.finish_reason = "error"
            context.should_stop = True
            logger.exception("Evaluation failed; stopping optimization.")
            raise
        if not isinstance(
            evaluation_result, (EvaluationResult, EvaluationResultOnDictItems)
        ):
            raise TypeError("Expected EvaluationResult from evaluate_prompt.")
        score = self._score_from_evaluation_result(
            evaluation_result,
            metric_name=context.metric.__name__,
            empty_score=empty_score,
        )
        coerced_score = self._coerce_score(score)
        prev_best_score = context.current_best_score
        self.on_trial(context, prompts, coerced_score, prev_best_score)
        self._should_stop_context(context)
        return coerced_score, evaluation_result

    # ------------------------------------------------------------------
    # Reporting/display
    # ------------------------------------------------------------------

    def _handle_trial_end(
        self,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        score: float,
        prev_best_score: float | None = None,
    ) -> None:
        """Common evaluation-complete logic (shared by legacy/new hooks)."""
        context.trials_completed += 1
        if prev_best_score is None or score > prev_best_score:
            context.current_best_score = score
            context.current_best_prompt = prompts
        runtime.evaluation_progress(
            optimizer=self,
            context=context,
            prompts=prompts,
            score=score,
            prev_best_score=prev_best_score,
        )
        debug_log(
            "evaluation_complete",
            trials_completed=context.trials_completed,
            score=score,
            best_score=context.current_best_score,
            dataset=getattr(context.evaluation_dataset, "name", None),
        )

    # ------------------------------------------------------------------
    # Optimization lifecycle
    # ------------------------------------------------------------------

    def run_optimization(
        self,
        context: OptimizationContext,
    ) -> AlgorithmResult:
        """
        Run the optimization algorithm.

        This is the method optimizers override to implement their algorithm.
        The method should:
        1. Control the optimization loop (when/how to generate candidates)
        2. Generate candidate prompts using algorithm-specific logic
        3. Call self.evaluate(context, prompts) for each candidate
        4. Check context.should_stop after evaluations
        5. Return AlgorithmResult with best prompt and score

        The framework (BaseOptimizer.optimize_prompt) handles:
        - Setup: validation, context creation, baseline computation
        - Trial tracking: context.trials_completed++ happens in evaluate()
        - Progress display: on_trial() hook handles all display
        - Early stop: evaluate() checks and sets context.should_stop
        - Result building: converts AlgorithmResult to OptimizationResult

        Args:
            context: The optimization context with prompts, dataset, metric, etc.

        Returns:
            AlgorithmResult.
        """
        return self._run_optimization(context)

    def _select_result_prompts(self, **kwargs: Any) -> tuple[Any, Any]:
        """Return the result prompt(s) and initial prompt(s) for output."""
        return runtime.select_result_prompts(
            best_prompts=kwargs["best_prompts"],
            initial_prompts=kwargs["initial_prompts"],
            is_single_prompt_optimization=kwargs["is_single_prompt_optimization"],
        )

    def _build_early_result(self, **kwargs: Any) -> OptimizationResult:
        """Build a baseline-only OptimizationResult when skipping optimization."""
        return runtime.build_early_result(**kwargs)

    def _build_final_result(
        self,
        algorithm_result: AlgorithmResult,
        context: OptimizationContext,
    ) -> OptimizationResult:
        return runtime.build_final_result(
            optimizer=self,
            algorithm_result=algorithm_result,
            context=context,
        )

    def cleanup(self) -> None:
        """
        Clean up resources and perform memory management.
        Should be called when the optimizer is no longer needed.
        """
        runtime.cleanup(self)

    def _set_reporter(self, reporter: Any | None) -> None:
        """Set the active reporter for the current optimization scope."""
        self._reporter = reporter

    def _clear_reporter(self) -> None:
        """Clear the active reporter."""
        self._reporter = None

    # ------------------------------------------------------------------
    # Opik client access
    # ------------------------------------------------------------------

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
        return runtime.get_opik_client(self)

    # ------------------------------------------------------------------
    # Experiment metadata & configuration
    # ------------------------------------------------------------------

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
        runtime.validate_inputs(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            support_content_parts=support_content_parts,
        )

    def _validate_metric_requirements(
        self,
        dataset: Dataset,
        metric: MetricFunction,
    ) -> None:
        required_fields = getattr(metric, "required_fields", None)
        if not required_fields:
            return

        items = dataset.get_items(1)
        if not items:
            raise ValueError("dataset is empty")
        sample = items[0]
        missing = [field for field in required_fields if not sample.get(field)]
        if missing:
            raise ValueError(
                f"Metric {metric.__name__} requires dataset fields {missing} "
                "but they are missing/empty in the evaluation dataset."
            )

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
        return runtime.extract_tool_prompts(tools)

    # ------------------------------------------------------------------
    # Sampling helpers
    # ------------------------------------------------------------------

    def _get_root_rng(self) -> random.Random:
        """Return the optimizer-scoped RNG."""
        return self._rng

    def _derive_rng(self, *tags: object) -> random.Random:
        """Derive a deterministic RNG for a sub-phase."""
        return rng_utils.derive_rng(self._get_root_rng(), *tags)

    def _prepare_sampling_plan(
        self,
        dataset: Dataset,
        n_samples: int | float | str | None,
        dataset_item_ids: list[str] | None = None,
        *,
        phase: str = "eval",
        seed_override: int | None = None,
        strategy: str | None = None,
    ) -> sampling.SamplingPlan:
        """Build a sampling plan for a dataset phase."""
        if dataset_item_ids is not None and n_samples is not None:
            raise ValueError("Can't use n_samples and dataset_item_ids together.")

        resolved_strategy = (
            strategy
            or getattr(self, "n_samples_strategy", None)
            or sampling.DEFAULT_STRATEGY
        )
        resolved_seed = seed_override if seed_override is not None else self.seed
        return sampling.resolve_sampling(
            dataset=dataset,
            n_samples=n_samples,
            dataset_item_ids=dataset_item_ids,
            phase=phase,
            seed=resolved_seed,
            strategy=resolved_strategy,
        )

    def _prepare_minibatch_plan(
        self,
        dataset: Dataset,
        n_samples: int | float | str | None,
        n_samples_minibatch: int | None,
        *,
        dataset_item_ids: list[str] | None = None,
        phase: str = "minibatch",
        seed_override: int | None = None,
        strategy: str | None = None,
    ) -> sampling.SamplingPlan:
        """Build a sampling plan for an inner-loop minibatch."""
        effective_n_samples = (
            n_samples_minibatch if n_samples_minibatch is not None else n_samples
        )
        return self._prepare_sampling_plan(
            dataset=dataset,
            n_samples=effective_n_samples,
            dataset_item_ids=dataset_item_ids,
            phase=phase,
            seed_override=seed_override,
            strategy=strategy,
        )

    @staticmethod
    def _build_sampling_tag(
        *,
        scope: str,
        round_index: int | None = None,
        candidate_id: str | None = None,
        batch_index: int | None = None,
        attempt: int | None = None,
    ) -> str:
        parts = [scope]
        if round_index is not None:
            parts.append(f"round:{round_index}")
        if candidate_id is not None:
            parts.append(f"candidate:{candidate_id}")
        if batch_index is not None:
            parts.append(f"batch:{batch_index}")
        if attempt is not None:
            parts.append(f"attempt:{attempt}")
        return ":".join(parts)

    # ------------------------------------------------------------------
    # Hooks (subclass extension points)
    # ------------------------------------------------------------------

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
        runtime.finalize_finish_reason(context)

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

    def pre_optimize(self, context: OptimizationContext) -> None:
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

    def post_optimize(
        self, context: OptimizationContext, result: OptimizationResult
    ) -> OptimizationResult:
        """Hook called after optimization completes."""
        return result

    def pre_baseline(self, context: OptimizationContext) -> None:
        """Hook fired before baseline evaluation starts."""
        return None

    def post_baseline(self, context: OptimizationContext, score: float) -> None:
        """Hook fired after baseline evaluation completes."""
        return None

    def pre_round(self, context: OptimizationContext, **extras: Any) -> Any:
        """Hook fired before a round begins."""
        handle = self._history_builder.start_round(extras=extras or None)
        debug_log(
            "round_context",
            round_index=handle,
            training_dataset=getattr(context.dataset, "name", None),
            evaluation_dataset=getattr(context.evaluation_dataset, "name", None),
            dataset_split=context.dataset_split,
        )
        return handle

    # Candidate-first aliases
    def begin_round(self, context: OptimizationContext, **extras: Any) -> Any:
        """Alias for pre_round."""
        return self.pre_round(context, **extras)

    def start_candidate(
        self,
        context: OptimizationContext,
        candidate: Any,
        *,
        round_handle: Any | None = None,
    ) -> Any:
        """Alias for pre_trial."""
        return self.pre_trial(context, candidate, round_handle=round_handle)

    def finish_candidate(
        self,
        context: OptimizationContext,
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
        """Alias for post_trial."""
        self.post_trial(
            context,
            candidate_handle,
            score=score,
            metrics=metrics,
            extras=extras,
            candidates=candidates,
            dataset=dataset,
            dataset_split=dataset_split,
            trial_index=trial_index,
            timestamp=timestamp,
            round_handle=round_handle,
        )

    def pre_trial(
        self,
        context: OptimizationContext,
        candidate: Any,
        round_handle: Any | None = None,
    ) -> Any:
        """Hook fired before a trial evaluation."""
        return candidate

    def on_trial(
        self,
        context: OptimizationContext,
        prompts: dict[str, chat_prompt.ChatPrompt],
        score: float,
        prev_best_score: float | None = None,
    ) -> None:
        """Hook fired after a trial evaluation (display/metrics)."""
        self._handle_trial_end(context, prompts, score, prev_best_score)

    def post_trial(
        self,
        context: OptimizationContext,
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
        """Hook fired after a trial to record history."""
        merged_extras = dict(extras or {})
        if self._last_evaluation_report is not None:
            merged_extras.setdefault("evaluation_report", self._last_evaluation_report)

        if hasattr(self._history_builder, "record_trial"):
            if dataset is None:
                dataset = getattr(context.evaluation_dataset, "name", None)
            if dataset_split is None:
                dataset_split = context.dataset_split
            stop_reason = None
            if context.should_stop:
                stop_reason = context.finish_reason
            self._history_builder.record_trial(
                round_handle=round_handle,
                score=score,
                candidate=candidate_handle,
                trial_index=trial_index,
                metrics=metrics,
                dataset=dataset,
                dataset_split=dataset_split,
                extras=merged_extras or None,
                candidates=candidates,
                timestamp=timestamp,
                stop_reason=stop_reason,
            )

    # ------------------------------------------------------------------
    # Orchestrated run flow
    # ------------------------------------------------------------------

    def _validate_and_prepare(
        self,
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None,
        experiment_config: dict | None,
        n_samples: int | float | str | None,
        n_samples_minibatch: int | None,
        n_samples_strategy: str | None,
        auto_continue: bool,
        project_name: str | None,
        optimization_id: str | None,
        validation_dataset: Dataset | None,
        max_trials: int,
        allow_tool_use: bool,
        optimize_prompt: bool | str | list[str] | None,
        extra_kwargs: dict[str, Any],
    ) -> OptimizationContext:
        # Reset counters at the start of each optimization run
        # (allows reusing the same optimizer instance for multiple runs)
        self._history_builder.clear()

        context = self._setup_optimization(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            agent=agent,
            n_samples=n_samples,
            n_samples_minibatch=n_samples_minibatch,
            n_samples_strategy=n_samples_strategy,
            experiment_config=experiment_config,
            validation_dataset=validation_dataset,
            project_name=project_name,
            optimization_id=optimization_id,
            max_trials=max_trials,
            auto_continue=auto_continue,
            allow_tool_use=allow_tool_use,
            optimize_prompt=optimize_prompt,
            **extra_kwargs,
        )
        debug_log(
            "optimize_start",
            optimizer=self.__class__.__name__,
            dataset=getattr(dataset, "name", None),
            max_trials=max_trials,
            n_samples=n_samples,
            n_samples_minibatch=n_samples_minibatch,
            n_samples_strategy=context.n_samples_strategy,
            n_threads=getattr(self, "n_threads", None),
        )

        if hasattr(self._history_builder, "set_context"):
            self._history_builder.set_context(context)

        runtime.show_run_start(optimizer=self, context=context)
        prompt_tracing.attach_initial_prompts(context.prompts)

        # Allow subclasses to perform pre-optimization setup (e.g., set self.agent)
        self.pre_optimize(context)

        return context

    def _run_baseline(self, context: OptimizationContext) -> float:
        """Compute and record the baseline score for the initial prompt."""
        baseline_score = self._calculate_baseline(context)
        context.baseline_score = baseline_score
        return baseline_score

    def _build_early_stop_result(
        self,
        *,
        context: OptimizationContext,
        baseline_score: float,
    ) -> OptimizationResult:
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

        runtime.show_final_result(
            optimizer=self,
            initial_score=baseline_score,
            best_score=baseline_score,
            prompt=early_result_prompt,
        )

        early_stop_details = runtime.build_early_stop_details(
            optimizer=self,
            context=context,
            baseline_score=baseline_score,
        )

        runtime.record_baseline_history(
            optimizer=self,
            context=context,
            prompt=early_result_prompt,
            score=baseline_score,
            stop_reason=early_stop_details.get("stop_reason"),
        )

        self._finalize_optimization(context, status="completed")
        debug_log(
            "optimize_end",
            optimizer=self.__class__.__name__,
            best_score=baseline_score,
            trials_completed=context.trials_completed,
            stop_reason=context.finish_reason,
        )
        result = runtime.build_early_stop_result(
            optimizer=self,
            context=context,
            baseline_score=baseline_score,
            prompt=early_result_prompt,
            initial_prompt=early_initial_prompt,
            details=early_stop_details,
        )
        return self.post_optimize(context, result)

    @staticmethod
    def _collect_optimize_args(
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None,
        experiment_config: dict | None,
        n_samples: int | float | str | None,
        n_samples_minibatch: int | None,
        n_samples_strategy: str | None,
        auto_continue: bool,
        project_name: str | None,
        optimization_id: str | None,
        validation_dataset: Dataset | None,
        max_trials: int,
        allow_tool_use: bool,
        optimize_prompt: bool | str | list[str] | None,
        extra_kwargs: dict[str, Any],
    ) -> dict[str, Any]:
        return {
            "prompt": prompt,
            "dataset": dataset,
            "metric": metric,
            "agent": agent,
            "experiment_config": experiment_config,
            "n_samples": n_samples,
            "n_samples_minibatch": n_samples_minibatch,
            "n_samples_strategy": n_samples_strategy,
            "auto_continue": auto_continue,
            "project_name": project_name,
            "optimization_id": optimization_id,
            "validation_dataset": validation_dataset,
            "max_trials": max_trials,
            "allow_tool_use": allow_tool_use,
            "optimize_prompt": optimize_prompt,
            "extra_kwargs": extra_kwargs,
        }

    def _run_algorithm_and_finalize(
        self,
        *,
        context: OptimizationContext,
        baseline_score: float,
    ) -> OptimizationResult:
        try:
            raw_result = self.run_optimization(context)

            # Finalize finish_reason if not set by optimizer
            self._finalize_finish_reason(context)

            if not isinstance(raw_result, AlgorithmResult):
                raise TypeError(
                    "run_optimization must return AlgorithmResult (legacy OptimizationResult is no longer supported)"
                )

            # Check if optimization failed (finish_reason = "error")
            # This can happen if evaluation fails but run_optimization() catches and returns
            if context.finish_reason == "error":
                logger.error("Optimization failed with error finish_reason")
                result = self._build_final_result(raw_result, context)
                result_prompt = runtime.select_result_display_prompt(result.prompt)
                runtime.show_final_result(
                    optimizer=self,
                    initial_score=(
                        result.initial_score
                        if result.initial_score is not None
                        else baseline_score
                    ),
                    best_score=result.score,
                    prompt=result_prompt,
                )
                self._finalize_optimization(context, status="error")
                debug_log(
                    "optimize_end",
                    optimizer=self.__class__.__name__,
                    best_score=result.score,
                    trials_completed=context.trials_completed,
                    stop_reason=context.finish_reason,
                )
                runtime.log_final_state(optimizer=self, result=result)
                return self.post_optimize(context, result)

            result = self._build_final_result(raw_result, context)

            result_prompt = runtime.select_result_display_prompt(result.prompt)
            runtime.show_final_result(
                optimizer=self,
                initial_score=(
                    result.initial_score
                    if result.initial_score is not None
                    else baseline_score
                ),
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
            runtime.log_final_state(optimizer=self, result=result)
            return self.post_optimize(context, result)
        except Exception as e:
            logger.error(f"Optimization failed: {e}")
            logger.error(
                f"Finalizing optimization with error status. "
                f"Optimization ID: {context.optimization_id}, "
                f"Optimization object exists: {context.optimization is not None}"
            )
            try:
                self._finalize_optimization(context, status="error")
            except Exception as finalize_error:
                logger.error(
                    f"Failed to finalize optimization status: {finalize_error}",
                    exc_info=True,
                )
            raise

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def optimize_prompt(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        agent: OptimizableAgent | None = None,
        experiment_config: dict | None = None,
        n_samples: int | float | str | None = None,
        n_samples_minibatch: int | None = None,
        n_samples_strategy: str | None = None,
        auto_continue: bool = False,
        project_name: str | None = None,
        optimization_id: str | None = None,
        validation_dataset: Dataset | None = None,
        max_trials: int = 10,
        allow_tool_use: bool = True,
        optimize_prompt: bool | str | list[str] | None = "system",
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
           n_samples_minibatch: Optional number of samples for inner-loop minibatches
           n_samples_strategy: Sampling strategy name (default "random_sorted")
               TODO: keep internal until the strategy set is stabilized.
           auto_continue: Whether to continue optimization automatically
           project_name: Opik project name for logging traces (defaults to OPIK_PROJECT_NAME env or "Optimization")
           optimization_id: Optional ID to use when creating the Opik optimization run
           validation_dataset: Optional validation dataset for ranking candidates
           max_trials: Maximum number of optimization trials
           allow_tool_use: Whether tools may be executed during evaluation (default True)
           optimize_prompt: Which prompt roles to allow for optimization
           **kwargs: Additional arguments passed to _setup_optimization and _run_optimization

        Returns:
            OptimizationResult with the optimized prompt and metrics
        """
        context = self._validate_and_prepare(
            **self._collect_optimize_args(
                prompt=prompt,
                dataset=dataset,
                metric=metric,
                agent=agent,
                experiment_config=experiment_config,
                n_samples=n_samples,
                n_samples_minibatch=n_samples_minibatch,
                n_samples_strategy=n_samples_strategy,
                auto_continue=auto_continue,
                project_name=project_name,
                optimization_id=optimization_id,
                validation_dataset=validation_dataset,
                max_trials=max_trials,
                allow_tool_use=allow_tool_use,
                optimize_prompt=optimize_prompt,
                extra_kwargs=kwargs,
            )
        )
        with runtime.handle_termination(optimizer=self, context=context):
            baseline_score = self._run_baseline(context)

            # Check for early stop if baseline meets threshold
            if self._should_skip_optimization(baseline_score):
                return self._build_early_stop_result(
                    context=context,
                    baseline_score=baseline_score,
                )

            return self._run_algorithm_and_finalize(
                context=context,
                baseline_score=baseline_score,
            )

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
        context: OptimizationContext | None = None,
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
        return entry

    def post_round(
        self,
        round_handle: Any,
        *,
        context: OptimizationContext | None = None,
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
        if stop_reason is None and context is not None and context.should_stop:
            stop_reason = context.finish_reason or "stopped"
        if context is not None:
            merged_extras = dict(extras or {})
            merged_extras.setdefault(
                "training_dataset", getattr(context.dataset, "name", None)
            )
            merged_extras.setdefault(
                "evaluation_dataset",
                getattr(context.evaluation_dataset, "name", None),
            )
            extras = merged_extras
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

    def finish_round(
        self,
        round_handle: Any,
        *,
        context: OptimizationContext | None = None,
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
        """Alias for post_round."""
        self.post_round(
            round_handle,
            context=context,
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
    ) -> bool:
        # FIXME: remove when a solution is added to opik's optimization.update method
        count = 0
        last_error = None
        while count < 3:
            try:
                optimization.update(status=status)
                logger.debug(f"Successfully updated optimization status to {status}")
                return True
            except ApiError as e:
                last_error = e
                count += 1
                logger.warning(
                    f"Attempt {count}/3 to update optimization status to {status} failed: {e}"
                )
                if count < 3:
                    time.sleep(5)
            except Exception as e:
                last_error = e
                logger.error(
                    f"Unexpected error updating optimization status to {status}: {e}"
                )
                break
        if count == 3 or last_error:
            logger.error(
                f"Unable to update optimization status to {status} after {count} attempts. "
                f"Last error: {last_error}"
            )
        return False

    @staticmethod
    def _coerce_score(raw_score: Any) -> float:
        """Normalize scores returned by metrics into builtin floats."""
        return runtime.coerce_score(raw_score)

    def _finalize_optimization(
        self,
        context: OptimizationContext,
        status: str = "completed",
    ) -> None:
        if context.optimization is not None:
            updated = self._update_optimization(context.optimization, status)
            if updated:
                logger.debug(
                    f"Optimization {context.optimization_id} status updated to {status}."
                )
            else:
                logger.warning(
                    f"Optimization {context.optimization_id} status update to {status} did not complete."
                )
        else:
            logger.warning(
                f"Cannot update optimization status to {status}: optimization object is None"
            )
        # No implicit context storage; nothing to clear here.

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
        n_samples: int | float | str | None = None,
        n_samples_strategy: str | None = None,
        seed: int | None = None,
        return_evaluation_result: Literal[True] = True,
        allow_tool_use: bool | None = None,
        use_evaluate_on_dict_items: bool | None = None,
        sampling_tag: str | None = None,
    ) -> EvaluationResult | EvaluationResultOnDictItems: ...

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
        n_samples: int | float | str | None = None,
        n_samples_strategy: str | None = None,
        seed: int | None = None,
        return_evaluation_result: Literal[False] = False,
        allow_tool_use: bool | None = None,
        use_evaluate_on_dict_items: bool | None = None,
        sampling_tag: str | None = None,
    ) -> float: ...

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
        n_samples: int | float | str | None = None,
        n_samples_strategy: str | None = None,
        seed: int | None = None,
        return_evaluation_result: bool = False,
        allow_tool_use: bool | None = None,
        use_evaluate_on_dict_items: bool | None = None,
        sampling_tag: str | None = None,
    ) -> float | EvaluationResult | EvaluationResultOnDictItems:
        sampling_seed = seed if seed is not None else self.seed
        n_threads = normalize_eval_threads(n_threads)
        if allow_tool_use is None:
            allow_tool_use = True
        if use_evaluate_on_dict_items is None:
            # TODO(opik-sdk): remove this flag once evaluate_on_dict_items is the default path.
            use_evaluate_on_dict_items = constants.ENABLE_EVALUATE_ON_DICT_ITEMS
        selection_rng = rng_utils.make_rng(
            sampling_seed, "candidate_selection", sampling_tag or ""
        )

        if agent is None:
            agent = LiteLLMAgent(project_name=self.project_name)
        self._set_agent_trace_phase(agent, "Evaluation")

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
                        rng=selection_rng,
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

                    self._update_trace_metadata(
                        {
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
            else:
                raw_model_output = agent.invoke_agent(
                    prompts=prompts_dict,
                    dataset_item=dataset_item,
                    allow_tool_use=bool(allow_tool_use),
                )
                cleaned_model_output = raw_model_output.strip()

            self._tag_trace(phase="Evaluation")

            result = {
                "llm_output": cleaned_model_output,
            }
            return result

        experiment_config = prepare_experiment_config(
            optimizer=self,
            prompt=prompt,
            agent=agent,
            dataset=dataset,
            metric=metric,
            experiment_config=experiment_config,
            configuration_updates=None,
            additional_metadata=None,
            validation_dataset=None,
            is_single_prompt_optimization=isinstance(prompt, chat_prompt.ChatPrompt),
            build_optimizer_version=_OPTIMIZER_VERSION,
        )

        phase = "eval" if sampling_tag is None else f"eval:{sampling_tag}"
        sampling_plan = self._prepare_sampling_plan(
            dataset=dataset,
            n_samples=n_samples,
            dataset_item_ids=dataset_item_ids,
            phase=phase,
            seed_override=sampling_seed,
            strategy=n_samples_strategy,
        )
        resolved_ids = sampling_plan.dataset_item_ids
        resolved_n_samples = (
            None if resolved_ids is not None else sampling_plan.nb_samples
        )
        debug_log(
            "evaluation_sampling",
            sampling_tag=sampling_tag,
            sampling_plan_mode=sampling_plan.mode,
            n_samples=n_samples,
            resolved_n_samples=resolved_n_samples,
            dataset_item_ids_count=len(resolved_ids) if resolved_ids else 0,
        )

        score, evaluation_result = task_evaluator.evaluate_with_result(
            dataset=dataset,
            evaluated_task=llm_task,
            metric=metric,
            num_threads=n_threads,
            dataset_item_ids=resolved_ids,
            project_name=self.project_name,
            experiment_config=experiment_config,
            optimization_id=self.current_optimization_id,
            verbose=verbose,
            n_samples=resolved_n_samples,
            use_evaluate_on_dict_items=use_evaluate_on_dict_items,
        )
        self._last_evaluation_report = task_evaluator.build_evaluation_report(
            evaluation_result=evaluation_result,
            objective_metric_name=metric.__name__,
        )
        if return_evaluation_result:
            if evaluation_result is None:
                raise ValueError("EvaluationResult is None, cannot return it")
            return evaluation_result
        return score
