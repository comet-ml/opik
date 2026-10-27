"""Simple Optuna-based optimizer for model parameter tuning."""

from collections.abc import Callable, Mapping
from typing import Any, NoReturn, TypedDict, cast

import copy
import logging
import warnings

import optuna

from opik import Dataset

from ...base_optimizer import BaseOptimizer
from ...core import runtime
from ...core.state import OptimizationContext, AlgorithmResult
from ...core.results import OptimizationResult
from ...agents import OptimizableAgent
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...utils.optuna_runtime import configure_optuna_logging
from .types import ParameterType
from .ops.optuna_ops import (
    build_optuna_objective,
    run_optuna_phase,
    completed_trials as get_completed_trials,
    select_best_trial,
    compute_parameter_importance,
)
from .ops.search_ops import ParameterSearchSpace
from . import prompts as param_prompts
from ... import constants
from . import helpers

logger = logging.getLogger(__name__)


class _BestState(TypedDict):
    score: float
    prompts: dict[str, chat_prompt.ChatPrompt]


class ParameterOptimizer(BaseOptimizer):
    supports_tool_optimization: bool = False
    supports_prompt_optimization: bool = False
    uses_reasoning: bool = False
    supports_multimodal: bool = True
    """
    The Parameter Optimizer uses Bayesian optimization to tune model parameters like
    temperature, top_p, and other LLM call parameters for optimal performance.

    This optimizer is ideal when you have a good prompt but want to fine-tune the
    model's behavior through parameter adjustments rather than prompt modifications.

    The evaluated model and its settings come from each ``ChatPrompt`` (``model`` and
    ``model_parameters``); the optimizer makes no LLM calls of its own.

    Args:
        model: Deprecated and ignored. Set ``model`` on the ChatPrompt instead.
        model_parameters: Deprecated and ignored. Set ``model_parameters`` on the
            ChatPrompt instead.
        default_n_trials: Default number of optimization trials to run
        local_search_ratio: Ratio of trials to dedicate to local search refinement (0.0-1.0)
        local_search_scale: Scale factor for narrowing search space during local search
        n_threads: Number of parallel threads for evaluation
        verbose: Controls internal logging/progress bars (0=off, 1=on)
        seed: Random seed for reproducibility
    """

    DEFAULT_PROMPTS: dict[str, str] = param_prompts.DEFAULT_PROMPTS

    def __init__(
        self,
        model: str | None = None,
        *,
        model_parameters: dict[str, Any] | None = None,
        default_n_trials: int = constants.PARAMETER_DEFAULT_N_TRIALS,
        local_search_ratio: float = constants.PARAMETER_DEFAULT_LOCAL_SEARCH_RATIO,
        local_search_scale: float = constants.PARAMETER_DEFAULT_LOCAL_SEARCH_SCALE,
        n_threads: int = constants.DEFAULT_NUM_THREADS,
        verbose: int = 1,
        seed: int = constants.DEFAULT_SEED,
        name: str | None = None,
        skip_perfect_score: bool = constants.DEFAULT_SKIP_PERFECT_SCORE,
        perfect_score: float = constants.DEFAULT_PERFECT_SCORE,
    ) -> None:
        if model is not None or model_parameters is not None:
            warnings.warn(
                "ParameterOptimizer ignores `model` and `model_parameters`: the evaluated "
                "model and its settings come from each ChatPrompt. Set `model` / "
                "`model_parameters` on the ChatPrompt instead; these arguments will be "
                "removed in a future release.",
                DeprecationWarning,
                stacklevel=2,
            )
        super().__init__(
            # No optimizer-side model: every LLM call uses the prompt's own model.
            model=cast(str, None),
            verbose=verbose,
            seed=seed,
            name=name,
            skip_perfect_score=skip_perfect_score,
            perfect_score=perfect_score,
        )
        self.default_n_trials = default_n_trials
        self.n_threads = n_threads
        self.local_search_ratio = max(0.0, min(local_search_ratio, 1.0))
        self.local_search_scale = max(0.0, local_search_scale)

        if self.verbose == 0:
            logger.setLevel(logging.WARNING)
        elif self.verbose == 1:
            logger.setLevel(logging.INFO)
        else:
            logger.setLevel(logging.DEBUG)

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
        *args: Any,
        **kwargs: Any,
    ) -> NoReturn:
        raise NotImplementedError(
            "ParameterOptimizer.optimize_prompt is not supported. "
            "Use optimize_parameter(prompt, dataset, metric, parameter_space) instead, "
            "where parameter_space is a ParameterSearchSpace or dict defining the parameters to optimize."
        )

    def get_optimizer_metadata(self) -> dict[str, Any]:
        return {}

    def get_config(self, context: OptimizationContext) -> dict[str, Any]:
        """Return optimizer-specific configuration for display."""
        return {
            "optimizer": self.__class__.__name__,
            "n_trials": self.default_n_trials,
            "n_samples": context.n_samples,
        }

    def _record_baseline_round(
        self,
        *,
        context: OptimizationContext,
        base_prompts: dict[str, chat_prompt.ChatPrompt],
        baseline_score: float,
        is_single_prompt_optimization: bool,
    ) -> None:
        first_prompt = list(base_prompts.values())[0]
        baseline_round = self.pre_round(context, stage="baseline", type="baseline")
        runtime.record_and_post_trial(
            optimizer=self,
            context=context,
            prompt_or_payload=base_prompts
            if not is_single_prompt_optimization
            else first_prompt,
            score=baseline_score,
            candidate_id="baseline",
            extra={
                "parameters": {},
                "model_kwargs": copy.deepcopy(first_prompt.model_kwargs or {}),
                "model": first_prompt.model,
                "type": "baseline",
                "stage": "baseline",
            },
            round_handle=baseline_round,
            post_extras={
                "parameters": {},
                "model_kwargs": copy.deepcopy(first_prompt.model_kwargs or {}),
                "model": first_prompt.model,
                "type": "baseline",
                "stage": "baseline",
            },
        )
        self.post_round(
            baseline_round,
            context=context,
            best_score=baseline_score,
            best_candidate=base_prompts
            if not is_single_prompt_optimization
            else first_prompt,
            stop_reason="baseline_score_met_threshold"
            if self._should_skip_optimization(baseline_score)
            else None,
            extras={
                "type": "baseline",
                "stage": "baseline",
            },
        )

    def optimize_parameter(
        self,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        parameter_space: ParameterSearchSpace | Mapping[str, Any],
        validation_dataset: Dataset | None = None,
        experiment_config: dict | None = None,
        max_trials: int | None = None,
        n_samples: int | float | str | None = None,
        n_samples_minibatch: int | None = None,
        n_samples_strategy: str | None = None,
        agent: OptimizableAgent | None = None,
        project_name: str = "Optimization",
        sampler: optuna.samplers.BaseSampler | None = None,
        callbacks: list[Callable[[optuna.study.Study, optuna.trial.FrozenTrial], None]]
        | None = None,
        timeout: float | None = None,
        local_trials: int | None = None,
        local_search_scale: float | None = None,
        optimization_id: str | None = None,
    ) -> OptimizationResult:
        """
        Optimize model parameters using Bayesian optimization.

        Supports both single prompts and dictionaries of prompts. When a dict is provided,
        the parameter space is automatically expanded to create independent parameters for
        each prompt (e.g., "temperature" becomes "prompt_a.temperature", "prompt_b.temperature").

        Example:
            prompts = {
                "prompt_a": ChatPrompt(name="prompt_a", system="A"),
                "prompt_b": ChatPrompt(name="prompt_b", system="B"),
            }
            parameter_space = ParameterSearchSpace(
                parameters=[
                    ParameterSpec(
                        name="temperature",
                        distribution=ParameterType.FLOAT,
                        low=0.0,
                        high=1.0,
                    )
                ]
            )
            # During optimization, the search space expands to:
            # - prompt_a.temperature
            # - prompt_b.temperature

        Args:
            prompt: The prompt or dict of prompts to evaluate with tuned parameters.
                When a dict is provided, parameters are optimized independently for each prompt.
            dataset: Dataset providing evaluation examples
            metric: Objective function to maximize
            parameter_space: Definition of the search space for tunable parameters.
                For multi-prompt, params without a prefix are expanded per prompt.
                Params already prefixed (e.g., 'analyze.temperature') are kept as-is.
            validation_dataset: Optional dataset used instead of dataset to rank baseline
                and trial configurations. Keep a separate test dataset for final evaluation.
            experiment_config: Optional experiment metadata
            max_trials: Total number of trials (if None, uses default_n_trials)
            n_samples: Number of dataset samples to evaluate per trial (None for all)
            n_samples_minibatch: Optional number of samples for inner-loop minibatches
            n_samples_strategy: Sampling strategy name (default "random_sorted")
            agent: Optional custom agent instance to execute evaluations
            project_name: Opik project name for logging traces (default: "Optimization")
            sampler: Optuna sampler to use (default: TPESampler with seed)
            callbacks: List of callback functions for Optuna study
            timeout: Maximum time in seconds for each search phase (global, then local).
                A run cut short by it reports ``finish_reason="timeout"``.
            local_trials: Number of trials for local search (overrides local_search_ratio)
            local_search_scale: Scale factor for local search narrowing (0.0-1.0)
            optimization_id: Optional ID of the Opik optimization run to log to. An existing
                run is reused; otherwise one is created with this ID (must be a valid UUIDv7).

        Returns:
            OptimizationResult with best prompts, score, history, and metadata.
        """
        prompts, single = self._normalize_prompt_input(prompt)
        if not isinstance(parameter_space, ParameterSearchSpace):
            parameter_space = ParameterSearchSpace.model_validate(parameter_space)
        assert isinstance(parameter_space, ParameterSearchSpace)
        prompts = helpers.build_base_prompts(prompts)
        expanded_space = parameter_space.expand_for_prompts(list(prompts))
        helpers.warn_unsupported_parameters(prompts, expanded_space)
        return super().optimize_prompt(
            prompt=next(iter(prompts.values())) if single else prompts,
            dataset=dataset,
            metric=metric,
            agent=agent,
            experiment_config=experiment_config,
            validation_dataset=validation_dataset,
            max_trials=self.default_n_trials
            if max_trials is None
            else max(0, max_trials),
            n_samples=n_samples,
            n_samples_minibatch=n_samples_minibatch,
            n_samples_strategy=n_samples_strategy,
            project_name=project_name,
            optimization_id=optimization_id,
            optimize_prompts=False,
            parameter_space=expanded_space,
            sampler=sampler,
            callbacks=callbacks,
            timeout=timeout,
            local_trials=local_trials,
            local_search_scale=local_search_scale,
        )

    def post_optimize(
        self, context: OptimizationContext, result: OptimizationResult
    ) -> OptimizationResult:
        # Keep parameter-specific fields on baseline-only results too.
        result.details.setdefault("n_trials", 0)
        result.details.setdefault("optimized_parameters", {})
        defaults = {
            "optimized_model_kwargs": {
                name: copy.deepcopy(prompt.model_kwargs)
                for name, prompt in context.prompts.items()
            },
            "optimized_model": next(iter(context.prompts.values())).model,
            "trials": [],
            "baseline_parameters": {
                name: copy.deepcopy(prompt.model_kwargs)
                for name, prompt in context.initial_prompts.items()
            },
            "local_trials": 0,
            "global_trials": 0,
            "search_stages": [],
            "search_ranges": {},
            "parameter_importance": {},
            "parameter_precision": 6,
        }
        for key, value in defaults.items():
            result.details.setdefault(key, value)
        if result.details.get("model") is None:
            result.details["model"] = {
                name: prompt.model for name, prompt in context.prompts.items()
            }
        result.details.setdefault(
            "parameter_space",
            context.extra_params["parameter_space"].model_dump(by_alias=True),
        )
        return result

    def run_optimization(self, context: OptimizationContext) -> AlgorithmResult:
        params = context.extra_params
        base_prompts = context.prompts
        baseline_parameters = {
            name: copy.deepcopy(prompt.model_kwargs)
            for name, prompt in base_prompts.items()
        }
        expanded_parameter_space = params["parameter_space"]
        baseline_score = context.baseline_score
        assert baseline_score is not None
        metric = context.metric
        agent = context.agent
        evaluation_dataset = context.evaluation_dataset
        experiment_config = context.experiment_config
        n_samples = context.n_samples
        n_samples_strategy = context.n_samples_strategy
        max_trials = context.max_trials
        sampler = params.get("sampler")
        callbacks = params.get("callbacks")
        timeout = params.get("timeout")
        local_trials_override = params.get("local_trials")
        local_search_scale_override = params.get("local_search_scale")
        self._record_baseline_round(
            context=context,
            base_prompts=base_prompts,
            baseline_score=baseline_score,
            is_single_prompt_optimization=context.is_single_prompt_optimization,
        )
        configure_optuna_logging(logger=logger)

        sampler = sampler or optuna.samplers.TPESampler(seed=self.seed)
        study = optuna.create_study(direction="maximize", sampler=sampler)

        total_trials, local_trials, global_trials = helpers.calculate_trial_counts(
            max_trials=max_trials,
            default_n_trials=self.default_n_trials,
            local_search_ratio=self.local_search_ratio,
            local_trials_override=local_trials_override,
        )

        current_space_ref: dict[str, ParameterSearchSpace] = {
            "space": expanded_parameter_space
        }
        stage_ref: dict[str, Any] = {"name": "global"}
        stage_counts: dict[str, int] = {}
        stage_records: list[dict[str, Any]] = []
        search_ranges: dict[str, dict[str, Any]] = {}
        best_state: _BestState = {
            "score": baseline_score,
            "prompts": copy.deepcopy(base_prompts),
        }
        context.max_trials = total_trials
        context.baseline_score = baseline_score
        context.current_best_score = baseline_score

        objective = build_optuna_objective(
            optimizer=self,
            context=context,
            current_space_ref=current_space_ref,
            stage_ref=stage_ref,
            stage_counts=stage_counts,
            best_state=cast(dict[str, Any], best_state),
            base_prompts=base_prompts,
            evaluation_dataset=evaluation_dataset,
            metric=metric,
            agent=agent,
            experiment_config=experiment_config,
            n_samples=n_samples,
            n_samples_strategy=n_samples_strategy,
            total_trials=total_trials,
        )

        run_optuna_phase(
            optimizer=self,
            study=study,
            objective=objective,
            n_trials=global_trials,
            timeout=timeout,
            callbacks=callbacks,
            stage_name="global",
            space=expanded_parameter_space,
            stage_ref=stage_ref,
            current_space_ref=current_space_ref,
            stage_records=stage_records,
            search_ranges=search_ranges,
            local_trials=local_trials,
            global_trials=global_trials,
            display_title="> Starting global search phase",
            display_description=f"│ Exploring full parameter space with {global_trials} trials",
        )

        best_score = baseline_score
        best_parameters: dict[str, Any] = {}
        best_model_kwargs: dict[str, Any] = {
            name: copy.deepcopy(p.model_kwargs or {})
            for name, p in base_prompts.items()
        }
        best_model: dict[str, str] = {name: p.model for name, p in base_prompts.items()}

        completed_trials = get_completed_trials(study)
        (
            best_score,
            best_parameters,
            best_model_kwargs,
            best_model,
        ) = select_best_trial(
            completed_trials=completed_trials,
            best_score=best_score,
            best_parameters=best_parameters,
            best_model_kwargs=best_model_kwargs,
            best_model=best_model,
        )

        local_space: ParameterSearchSpace | None = None
        if (
            not context.should_stop
            and local_trials > 0
            and completed_trials
            and any(
                spec.distribution in {ParameterType.FLOAT, ParameterType.INT}
                for spec in expanded_parameter_space.parameters
            )
        ):
            local_scale = (
                self.local_search_scale
                if local_search_scale_override is None
                else max(0.0, float(local_search_scale_override))
            )

            if best_parameters:
                center_values = best_parameters
            else:
                center_values = {}

            if local_scale > 0 and center_values:
                local_space = expanded_parameter_space.narrow_around(
                    center_values, local_scale
                )
                run_optuna_phase(
                    optimizer=self,
                    study=study,
                    objective=objective,
                    n_trials=local_trials,
                    timeout=timeout,
                    callbacks=callbacks,
                    stage_name="local",
                    space=local_space,
                    stage_ref=stage_ref,
                    current_space_ref=current_space_ref,
                    stage_records=stage_records,
                    search_ranges=search_ranges,
                    scale=local_scale,
                    local_trials=local_trials,
                    global_trials=global_trials,
                    display_title="> Starting local search phase",
                    display_description=(
                        f"│ Refining around best parameters with {local_trials} trials (scale: {local_scale})"
                    ),
                )

                completed_trials = get_completed_trials(study)
                (
                    best_score,
                    best_parameters,
                    best_model_kwargs,
                    best_model,
                ) = select_best_trial(
                    completed_trials=completed_trials,
                    best_score=best_score,
                    best_parameters=best_parameters,
                    best_model_kwargs=best_model_kwargs,
                    best_model=best_model,
                )

        else:
            local_trials = 0

        if (
            timeout is not None
            and not context.should_stop
            and len(completed_trials) < total_trials
        ):
            context.finish_reason = "timeout"

        importance = compute_parameter_importance(
            study=study,
            completed_trials=completed_trials,
            expanded_parameter_space=expanded_parameter_space,
        )

        history_entries = self.get_history_entries()
        details = {
            "initial_score": baseline_score,
            "optimized_parameters": best_parameters,
            "optimized_model_kwargs": best_model_kwargs,
            "optimized_model": best_model,
            "trials": history_entries,
            "parameter_space": expanded_parameter_space.model_dump(by_alias=True),
            "n_trials": total_trials,
            "model": best_model,
            "baseline_parameters": baseline_parameters,
            "local_trials": local_trials,
            "global_trials": global_trials,
            "search_stages": stage_records,
            "search_ranges": search_ranges,
            "parameter_importance": importance,
            "parameter_precision": 6,
            "trials_requested": total_trials,
            "trials_completed": len(completed_trials),
            "selection_meta": {
                "sampler": sampler.__class__.__name__ if sampler else None,
                "pruner": type(study.pruner).__name__ if study.pruner else None,
            },
        }

        return AlgorithmResult(
            best_prompts=best_state["prompts"],
            best_score=best_score,
            history=history_entries,
            metadata=details,
        )
