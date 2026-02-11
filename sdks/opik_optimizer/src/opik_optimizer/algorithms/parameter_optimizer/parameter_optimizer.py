"""Simple Optuna-based optimizer for model parameter tuning."""

from collections.abc import Callable, Mapping
from typing import Any, NoReturn, TypedDict, cast

import copy
import logging

import optuna

from opik import Dataset

from ...base_optimizer import BaseOptimizer
from ...core import runtime
from ...core.state import OptimizationContext, AlgorithmResult
from ...core.results import OptimizationResult
from ...agents import OptimizableAgent
from ...agents import LiteLLMAgent
from ...api_objects import chat_prompt
from ...api_objects.types import MetricFunction
from ...utils import display as display_utils
from ...utils.optuna_runtime import configure_optuna_logging
from ...utils.logging import debug_log
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
from . import reporting
from . import helpers

logger = logging.getLogger(__name__)


class _BestState(TypedDict):
    score: float
    prompts: dict[str, chat_prompt.ChatPrompt]


class ParameterOptimizer(BaseOptimizer):
    supports_tool_optimization: bool = False
    supports_prompt_optimization: bool = False
    supports_multimodal: bool = True
    """
    The Parameter Optimizer uses Bayesian optimization to tune model parameters like
    temperature, top_p, and other LLM call parameters for optimal performance.

    This optimizer is ideal when you have a good prompt but want to fine-tune the
    model's behavior through parameter adjustments rather than prompt modifications.

    Args:
        model: LiteLLM model name (used for metadata, not for optimization calls)
        model_parameters: Optional dict of LiteLLM parameters for optimizer's internal LLM calls.
            Common params: temperature, max_tokens, max_completion_tokens, top_p.
            See: https://docs.litellm.ai/docs/completion/input
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
        model: str = constants.DEFAULT_MODEL,
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
        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
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

    def run_optimization(self, context: OptimizationContext) -> AlgorithmResult:
        raise NotImplementedError(
            "ParameterOptimizer does not use the standard run_optimization flow. "
            "Use optimize_parameter() instead."
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

    def _prepare_parameter_context(
        self,
        *,
        prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
        dataset: Dataset,
        metric: MetricFunction,
        parameter_space: ParameterSearchSpace | Mapping[str, Any],
        validation_dataset: Dataset | None,
        experiment_config: dict | None,
        n_samples: int | float | str | None,
        n_samples_minibatch: int | None,
        n_samples_strategy: str | None,
        agent: OptimizableAgent | None,
        project_name: str,
        optimization_id: str | None,
        max_trials: int,
    ) -> tuple[
        OptimizationContext,
        dict[str, chat_prompt.ChatPrompt],
        dict[str, Any],
        ParameterSearchSpace,
        bool,
        Dataset,
        dict | None,
    ]:
        self.project_name = project_name
        self._reset_counters()
        self.set_default_dataset_split("validation" if validation_dataset else "train")

        if agent is None:
            agent = LiteLLMAgent(project_name=project_name)

        prompts, is_single_prompt_optimization = self._normalize_prompt_input(prompt)

        if not isinstance(parameter_space, ParameterSearchSpace):
            parameter_space = ParameterSearchSpace.model_validate(parameter_space)

        self._validate_optimization_inputs(
            prompts, dataset, metric, support_content_parts=True
        )

        if validation_dataset is not None:
            logger.warning(
                f"Due to the internal implementation of {self.__class__.__name__}, it currently "
                "fully ignores the `dataset` if `validation_dataset` is provided. We recommend not "
                "using the `validation_dataset` parameter."
            )
            experiment_config = experiment_config or {}
            experiment_config["validation_dataset"] = validation_dataset.name
            experiment_config["validation_dataset_id"] = validation_dataset.id

        evaluation_dataset = self._select_evaluation_dataset(
            dataset, validation_dataset
        )

        assert isinstance(parameter_space, ParameterSearchSpace)
        prompt_names = list(prompts.keys())
        expanded_parameter_space = parameter_space.expand_for_prompts(prompt_names)

        base_model_kwargs = helpers.build_base_model_kwargs(self.model_parameters)
        base_prompts = helpers.build_base_prompts(
            prompts, self.model, base_model_kwargs
        )

        optimization = self._create_optimization_run(dataset, metric, optimization_id)

        display_utils.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=self.current_optimization_id,
            dataset_id=dataset.id,
            verbose=self.verbose,
        )

        display_prompt = (
            list(base_prompts.values())[0]
            if is_single_prompt_optimization
            else base_prompts
        )
        display_utils.display_configuration(
            messages=display_prompt,
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "n_trials": max_trials,
                "n_samples": n_samples,
                "n_threads": self.n_threads,
                "local_search_ratio": self.local_search_ratio,
                "local_search_scale": self.local_search_scale,
            },
            verbose=self.verbose,
        )

        context = OptimizationContext(
            prompts=base_prompts,
            initial_prompts=copy.deepcopy(base_prompts),
            is_single_prompt_optimization=is_single_prompt_optimization,
            dataset=dataset,
            evaluation_dataset=evaluation_dataset,
            validation_dataset=validation_dataset,
            metric=metric,
            agent=agent,
            optimization=optimization,
            optimization_id=self.current_optimization_id,
            experiment_config=experiment_config,
            n_samples=n_samples,
            n_samples_minibatch=n_samples_minibatch,
            n_samples_strategy=n_samples_strategy
            or constants.DEFAULT_N_SAMPLES_STRATEGY,
            max_trials=max_trials or self.default_n_trials,
            project_name=project_name,
            allow_tool_use=True,
            baseline_score=None,
            extra_params={},
        )
        return (
            context,
            base_prompts,
            base_model_kwargs,
            expanded_parameter_space,
            is_single_prompt_optimization,
            evaluation_dataset,
            experiment_config,
        )

    def _evaluate_baseline_score(
        self,
        *,
        context: OptimizationContext,
        base_prompts: dict[str, chat_prompt.ChatPrompt],
        agent: OptimizableAgent | None,
        evaluation_dataset: Dataset,
        metric: MetricFunction,
        experiment_config: dict | None,
        n_samples: int | float | str | None,
        n_samples_strategy: str | None = None,
    ) -> float:
        with reporting.display_evaluation(
            verbose=self.verbose,
            selection_summary=display_utils.summarize_selection_policy(base_prompts),
        ) as baseline_reporter:
            self.pre_trial(context, base_prompts)
            baseline_score = self.evaluate_prompt(
                prompt=base_prompts,
                agent=agent,
                dataset=evaluation_dataset,
                metric=metric,
                n_threads=self.n_threads,
                verbose=self.verbose,
                experiment_config=experiment_config,
                n_samples=n_samples,
                n_samples_strategy=n_samples_strategy or context.n_samples_strategy,
            )
            baseline_reporter.set_score(baseline_score)
        return baseline_score

    def _record_baseline_round(
        self,
        *,
        context: OptimizationContext,
        base_prompts: dict[str, chat_prompt.ChatPrompt],
        base_model_kwargs: dict[str, Any],
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

    def _build_early_baseline_result(
        self,
        *,
        context: OptimizationContext,
        base_prompts: dict[str, chat_prompt.ChatPrompt],
        base_model_kwargs: dict[str, Any],
        baseline_score: float,
        metric: MetricFunction,
        expanded_parameter_space: ParameterSearchSpace,
        is_single_prompt_optimization: bool,
    ) -> OptimizationResult:
        display_prompt = (
            list(base_prompts.values())[0]
            if is_single_prompt_optimization
            else base_prompts
        )
        display_utils.display_result(
            initial_score=baseline_score,
            best_score=baseline_score,
            prompt=display_prompt,
            verbose=self.verbose,
        )
        early_result_prompt, early_initial_prompt = self._select_result_prompts(
            best_prompts=base_prompts,
            initial_prompts=base_prompts,
            is_single_prompt_optimization=is_single_prompt_optimization,
        )
        return self._build_early_result(
            optimizer_name=self.__class__.__name__,
            prompt=early_result_prompt,
            initial_prompt=early_initial_prompt,
            score=baseline_score,
            metric_name=metric.__name__,
            details={
                "initial_score": baseline_score,
                "optimized_parameters": {},
                "optimized_model_kwargs": base_model_kwargs,
                "optimized_model": list(base_prompts.values())[0].model,
                "trials": [],
                "parameter_space": expanded_parameter_space.model_dump(by_alias=True),
                "n_trials": 0,
                "model": list(base_prompts.values())[0].model,
                "rounds": [],
                "baseline_parameters": base_model_kwargs,
                "local_trials": 0,
                "global_trials": 0,
                "search_stages": [],
                "search_ranges": {},
                "parameter_importance": {},
                "parameter_precision": 6,
                "stopped_early": True,
                "stop_reason": "baseline_score_met_threshold",
                "perfect_score": self.perfect_score,
                "skip_perfect_score": self.skip_perfect_score,
            },
            history=self.get_history_entries(),
            llm_calls=self.llm_call_counter,
            llm_calls_tools=self.llm_call_tools_counter,
            optimization_id=self.current_optimization_id,
            dataset_id=context.dataset.id,
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
            validation_dataset: Optional validation dataset. Note: Due to the internal implementation
                of ParameterOptimizer, this parameter is currently not fully utilized and we recommend
                not using it for this optimizer.
            experiment_config: Optional experiment metadata
            max_trials: Total number of trials (if None, uses default_n_trials)
            n_samples: Number of dataset samples to evaluate per trial (None for all)
            n_samples_minibatch: Optional number of samples for inner-loop minibatches
            n_samples_strategy: Sampling strategy name (default "random_sorted")
            agent: Optional custom agent instance to execute evaluations
            project_name: Opik project name for logging traces (default: "Optimization")
            sampler: Optuna sampler to use (default: TPESampler with seed)
            callbacks: List of callback functions for Optuna study
            timeout: Maximum time in seconds for optimization
            local_trials: Number of trials for local search (overrides local_search_ratio)
            local_search_scale: Scale factor for local search narrowing (0.0-1.0)
            optimization_id: Optional ID to use when creating the Opik optimization run;
                when provided it must be a valid UUIDv7 string.

        Returns:
            AlgorithmResult with best prompts, score, history, and metadata.
        """
        (
            context,
            base_prompts,
            base_model_kwargs,
            expanded_parameter_space,
            is_single_prompt_optimization,
            evaluation_dataset,
            experiment_config,
        ) = self._prepare_parameter_context(
            prompt=prompt,
            dataset=dataset,
            metric=metric,
            parameter_space=parameter_space,
            validation_dataset=validation_dataset,
            experiment_config=experiment_config,
            n_samples=n_samples,
            n_samples_minibatch=n_samples_minibatch,
            n_samples_strategy=n_samples_strategy,
            agent=agent,
            project_name=project_name,
            optimization_id=optimization_id,
            max_trials=max_trials or self.default_n_trials,
        )
        optimization = context.optimization
        local_trials_override = local_trials
        local_search_scale_override = local_search_scale

        baseline_score = self._evaluate_baseline_score(
            context=context,
            base_prompts=base_prompts,
            agent=context.agent,
            evaluation_dataset=evaluation_dataset,
            metric=metric,
            experiment_config=experiment_config,
            n_samples=n_samples,
            n_samples_strategy=n_samples_strategy,
        )

        if self._should_skip_optimization(baseline_score):
            logger.info(
                "Baseline score %.4f >= %.4f; skipping parameter optimization.",
                baseline_score,
                self.perfect_score,
            )
            self._history_builder.clear()
            self._record_baseline_round(
                context=context,
                base_prompts=base_prompts,
                base_model_kwargs=base_model_kwargs,
                baseline_score=baseline_score,
                is_single_prompt_optimization=is_single_prompt_optimization,
            )
            return self._build_early_baseline_result(
                context=context,
                base_prompts=base_prompts,
                base_model_kwargs=base_model_kwargs,
                baseline_score=baseline_score,
                metric=metric,
                expanded_parameter_space=expanded_parameter_space,
                is_single_prompt_optimization=is_single_prompt_optimization,
            )

        self._history_builder.clear()
        self._record_baseline_round(
            context=context,
            base_prompts=base_prompts,
            base_model_kwargs=base_model_kwargs,
            baseline_score=baseline_score,
            is_single_prompt_optimization=is_single_prompt_optimization,
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
            base_model_kwargs=base_model_kwargs,
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
            local_trials > 0
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

        importance = compute_parameter_importance(
            study=study,
            completed_trials=completed_trials,
            expanded_parameter_space=expanded_parameter_space,
        )

        display_prompt = (
            list(best_state["prompts"].values())[0]
            if is_single_prompt_optimization
            else best_state["prompts"]
        )
        display_utils.display_result(
            initial_score=baseline_score,
            best_score=best_score,
            prompt=display_prompt,
            verbose=self.verbose,
        )

        if optimization is not None:
            self._update_optimization(optimization, status="completed")
            logger.info(
                "Optimization %s status updated to completed.",
                self.current_optimization_id,
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
            "baseline_parameters": base_model_kwargs,
            "local_trials": local_trials,
            "global_trials": global_trials,
            "search_stages": stage_records,
            "search_ranges": search_ranges,
            "parameter_importance": importance,
            "parameter_precision": 6,
            "trials_requested": total_trials,
            "trials_completed": len(completed_trials),
            "stopped_early": len(completed_trials) < total_trials,
            "stop_reason": None,
            "selection_meta": {
                "sampler": sampler.__class__.__name__ if sampler else None,
                "pruner": type(study.pruner).__name__ if study.pruner else None,
            },
        }

        # Prepare result prompt based on single vs multi-prompt optimization
        result_prompt, initial_prompt_result = self._select_result_prompts(
            best_prompts=best_state["prompts"],
            initial_prompts=base_prompts,
            is_single_prompt_optimization=is_single_prompt_optimization,
        )

        result = OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=result_prompt,
            initial_prompt=initial_prompt_result,
            initial_score=baseline_score,
            score=best_score,
            metric_name=metric.__name__,
            details=details,
            history=self.get_history_entries(),
            llm_calls=self.llm_call_counter,
            llm_calls_tools=self.llm_call_tools_counter,
            optimization_id=self.current_optimization_id,
            dataset_id=dataset.id,
        )
        debug_log(
            "optimize_end",
            optimizer=self.__class__.__name__,
            best_score=best_score,
            trials_completed=len(completed_trials),
            stop_reason=None,
        )
        runtime.log_final_state(optimizer=self, result=result)
        return result
