"""Simple Optuna-based optimizer for model parameter tuning."""

from collections.abc import Callable, Mapping
from typing import Any

import copy
import logging
from datetime import datetime

import optuna
from optuna import importance as optuna_importance
from optuna.trial import Trial, TrialState

from opik import Dataset

from ...base_optimizer import BaseOptimizer
from ...optimizable_agent import OptimizableAgent
from ...api_objects import chat_prompt
from ...optimization_result import OptimizationResult
from .parameter_search_space import ParameterSearchSpace
from .search_space_types import ParameterType
from .sensitivity_analysis import compute_sensitivity_from_trials
from . import reporting

logger = logging.getLogger(__name__)


class ParameterOptimizer(BaseOptimizer):
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

    def __init__(
        self,
        model: str = "gpt-4o",
        *,
        model_parameters: dict[str, Any] | None = None,
        default_n_trials: int = 20,
        local_search_ratio: float = 0.3,
        local_search_scale: float = 0.2,
        n_threads: int = 4,
        verbose: int = 1,
        seed: int = 42,
        name: str | None = None,
    ) -> None:
        super().__init__(
            model=model,
            verbose=verbose,
            seed=seed,
            model_parameters=model_parameters,
            name=name,
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
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable,
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        project_name: str = "Optimization",
        *args: Any,
        **kwargs: Any,
    ) -> OptimizationResult:
        raise NotImplementedError(
            "ParameterOptimizer.optimize_prompt is not supported. "
            "Use optimize_parameter(prompt, dataset, metric, parameter_space) instead, "
            "where parameter_space is a ParameterSearchSpace or dict defining the parameters to optimize."
        )

    def optimize_parameter(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable[[Any, Any], float],
        parameter_space: ParameterSearchSpace | Mapping[str, Any],
        experiment_config: dict | None = None,
        max_trials: int | None = None,
        n_samples: int | None = None,
        agent_class: type[OptimizableAgent] | None = None,
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

        Args:
            prompt: The prompt to evaluate with tuned parameters
            dataset: Dataset providing evaluation examples
            metric: Objective function to maximize
            parameter_space: Definition of the search space for tunable parameters
            experiment_config: Optional experiment metadata
            max_trials: Total number of trials (if None, uses default_n_trials)
            n_samples: Number of dataset samples to evaluate per trial (None for all)
            agent_class: Optional custom agent class to execute evaluations
            sampler: Optuna sampler to use (default: TPESampler with seed)
            callbacks: List of callback functions for Optuna study
            timeout: Maximum time in seconds for optimization
            local_trials: Number of trials for local search (overrides local_search_ratio)
            local_search_scale: Scale factor for local search narrowing (0.0-1.0)
            optimization_id: Optional ID to use when creating the Opik optimization run;
                when provided it must be a valid UUIDv7 string.

        Returns:
            OptimizationResult: Structured result describing the best parameters found
        """
        if not isinstance(parameter_space, ParameterSearchSpace):
            parameter_space = ParameterSearchSpace.model_validate(parameter_space)

        # After validation, parameter_space is guaranteed to be ParameterSearchSpace
        assert isinstance(parameter_space, ParameterSearchSpace)  # for mypy

        local_trials_override = local_trials
        local_search_scale_override = local_search_scale

        self._validate_optimization_inputs(prompt, dataset, metric)

        base_model_kwargs = copy.deepcopy(prompt.model_kwargs or {})
        base_prompt = prompt.copy()
        base_prompt.model_kwargs = copy.deepcopy(base_model_kwargs)

        metric_name = getattr(metric, "__name__", str(metric))

        # Create optimization run
        optimization = self.opik_client.create_optimization(
            dataset_name=dataset.name,
            objective_name=metric_name,
            name=self.name,
            metadata=self._build_optimization_config(),
            optimization_id=optimization_id,
        )
        self.current_optimization_id = optimization.id
        logger.debug(f"Created optimization with ID: {optimization.id}")

        # Display header with optimization link
        reporting.display_header(
            algorithm=self.__class__.__name__,
            optimization_id=optimization.id,
            dataset_id=dataset.id,
            verbose=self.verbose,
        )

        # Display configuration
        reporting.display_configuration(
            messages=prompt.get_messages(),
            optimizer_config={
                "optimizer": self.__class__.__name__,
                "n_trials": max_trials
                if max_trials is not None
                else self.default_n_trials,
                "n_samples": n_samples,
                "n_threads": self.n_threads,
                "local_search_ratio": self.local_search_ratio,
                "local_search_scale": self.local_search_scale,
            },
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
        )

        self.agent_class = self._setup_agent_class(base_prompt, agent_class)

        # Evaluate baseline with reporting
        with reporting.display_evaluation(verbose=self.verbose) as baseline_reporter:
            baseline_score = self.evaluate_prompt(
                prompt=base_prompt,
                dataset=dataset,
                metric=metric,
                n_threads=self.n_threads,
                verbose=self.verbose,
                experiment_config=experiment_config,
                n_samples=n_samples,
                agent_class=self.agent_class,
            )
            baseline_reporter.set_score(baseline_score)

        history: list[dict[str, Any]] = [
            {
                "iteration": 0,
                "timestamp": datetime.utcnow().isoformat(),
                "parameters": {},
                "score": baseline_score,
                "model_kwargs": copy.deepcopy(base_prompt.model_kwargs or {}),
                "model": base_prompt.model,
                "type": "baseline",
                "stage": "baseline",
            }
        ]

        try:
            optuna.logging.disable_default_handler()
            optuna_logger = logging.getLogger("optuna")
            optuna_logger.setLevel(logger.getEffectiveLevel())
            optuna_logger.propagate = False
        except Exception as exc:  # pragma: no cover - defensive safety
            logger.warning("Could not configure Optuna logging: %s", exc)

        sampler = sampler or optuna.samplers.TPESampler(seed=self.seed)
        study = optuna.create_study(direction="maximize", sampler=sampler)

        total_trials = self.default_n_trials if max_trials is None else max_trials
        if total_trials < 0:
            total_trials = 0

        if local_trials_override is not None:
            local_trials = min(max(int(local_trials_override), 0), total_trials)
        else:
            local_trials = int(total_trials * self.local_search_ratio)

        global_trials = total_trials - local_trials
        if total_trials > 0 and global_trials <= 0:
            global_trials = 1
            local_trials = max(0, total_trials - global_trials)

        current_space = parameter_space
        current_stage = "global"
        stage_records: list[dict[str, Any]] = []
        search_ranges: dict[str, dict[str, Any]] = {}
        current_best_score = baseline_score

        def objective(trial: Trial) -> float:
            nonlocal current_best_score

            sampled_values = current_space.suggest(trial)
            tuned_prompt = parameter_space.apply(
                prompt,
                sampled_values,
                base_model_kwargs=base_model_kwargs,
            )
            tuned_agent_class = self._setup_agent_class(tuned_prompt, agent_class)

            # Display trial evaluation with parameters
            with reporting.display_trial_evaluation(
                trial_number=trial.number,
                total_trials=total_trials,
                stage=current_stage,
                parameters=sampled_values,
                verbose=self.verbose,
            ) as trial_reporter:
                score = self.evaluate_prompt(
                    prompt=tuned_prompt,
                    dataset=dataset,
                    metric=metric,
                    n_threads=self.n_threads,
                    verbose=self.verbose,
                    experiment_config=experiment_config,
                    n_samples=n_samples,
                    agent_class=tuned_agent_class,
                )

                # Check if this is a new best
                is_best = score > current_best_score
                if is_best:
                    current_best_score = score

                trial_reporter.set_score(score, is_best=is_best)

            trial.set_user_attr("parameters", sampled_values)
            trial.set_user_attr(
                "model_kwargs", copy.deepcopy(tuned_prompt.model_kwargs)
            )
            trial.set_user_attr("model", tuned_prompt.model)
            trial.set_user_attr("stage", current_stage)
            return float(score)

        global_range = parameter_space.describe()
        stage_records.append(
            {
                "stage": "global",
                "trials": global_trials,
                "scale": 1.0,
                "parameters": global_range,
            }
        )
        search_ranges["global"] = global_range

        if global_trials > 0:
            if self.verbose >= 1:
                from rich.text import Text
                from rich.console import Console

                console = Console()
                console.print("")
                console.print(Text("> Starting global search phase", style="bold cyan"))
                console.print(
                    Text(
                        f"│ Exploring full parameter space with {global_trials} trials"
                    )
                )
                console.print("")

            study.optimize(
                objective,
                n_trials=global_trials,
                timeout=timeout,
                callbacks=callbacks,
                show_progress_bar=False,
            )

        for trial in study.trials:
            if trial.state != TrialState.COMPLETE or trial.value is None:
                continue
            timestamp = (
                trial.datetime_complete or trial.datetime_start or datetime.utcnow()
            )
            history.append(
                {
                    "iteration": trial.number + 1,
                    "timestamp": timestamp.isoformat(),
                    "parameters": trial.user_attrs.get("parameters", {}),
                    "score": float(trial.value),
                    "model_kwargs": trial.user_attrs.get("model_kwargs"),
                    "model": trial.user_attrs.get("model"),
                    "stage": trial.user_attrs.get("stage", "global"),
                }
            )

        best_score = baseline_score
        best_parameters: dict[str, Any] = {}
        best_model_kwargs = copy.deepcopy(base_prompt.model_kwargs or {})
        best_model = base_prompt.model

        completed_trials = [
            trial
            for trial in study.trials
            if trial.state == TrialState.COMPLETE and trial.value is not None
        ]
        if completed_trials:
            best_trial = max(completed_trials, key=lambda t: t.value)  # type: ignore[arg-type]
            if best_trial.value is not None and best_trial.value > best_score:
                best_score = float(best_trial.value)
                best_parameters = best_trial.user_attrs.get("parameters", {})
                best_model_kwargs = best_trial.user_attrs.get("model_kwargs", {})
                best_model = best_trial.user_attrs.get("model", prompt.model)

        local_space: ParameterSearchSpace | None = None
        if (
            local_trials > 0
            and completed_trials
            and any(
                spec.distribution in {ParameterType.FLOAT, ParameterType.INT}
                for spec in parameter_space.parameters
            )
        ):
            local_scale = (
                self.local_search_scale
                if local_search_scale_override is None
                else max(0.0, float(local_search_scale_override))
            )

            if best_parameters:
                center_values = best_parameters
            elif base_model_kwargs:
                center_values = base_model_kwargs
            else:
                center_values = {}

            if local_scale > 0 and center_values:
                current_stage = "local"
                local_space = parameter_space.narrow_around(center_values, local_scale)
                local_range = local_space.describe()
                stage_records.append(
                    {
                        "stage": "local",
                        "trials": local_trials,
                        "scale": local_scale,
                        "parameters": local_range,
                    }
                )
                search_ranges["local"] = local_range

                if self.verbose >= 1:
                    from rich.text import Text
                    from rich.console import Console

                    console = Console()
                    console.print("")
                    console.print(
                        Text("> Starting local search phase", style="bold cyan")
                    )
                    console.print(
                        Text(
                            f"│ Refining around best parameters with {local_trials} trials (scale: {local_scale})"
                        )
                    )
                    console.print("")

                current_space = local_space
                study.optimize(
                    objective,
                    n_trials=local_trials,
                    timeout=timeout,
                    callbacks=callbacks,
                    show_progress_bar=False,
                )

                completed_trials = [
                    trial
                    for trial in study.trials
                    if trial.state == TrialState.COMPLETE and trial.value is not None
                ]
                if completed_trials:
                    new_best = max(completed_trials, key=lambda t: t.value)  # type: ignore[arg-type]
                    if new_best.value is not None and new_best.value > best_score:
                        best_score = float(new_best.value)
                        best_parameters = new_best.user_attrs.get("parameters", {})
                        best_model_kwargs = new_best.user_attrs.get("model_kwargs", {})
                        best_model = new_best.user_attrs.get("model", prompt.model)

        else:
            local_trials = 0

        for trial in study.trials:
            if trial.state != TrialState.COMPLETE or trial.value is None:
                continue
            timestamp = (
                trial.datetime_complete or trial.datetime_start or datetime.utcnow()
            )
            if not any(entry["iteration"] == trial.number + 1 for entry in history):
                history.append(
                    {
                        "iteration": trial.number + 1,
                        "timestamp": timestamp.isoformat(),
                        "parameters": trial.user_attrs.get("parameters", {}),
                        "score": float(trial.value),
                        "model_kwargs": trial.user_attrs.get("model_kwargs"),
                        "model": trial.user_attrs.get("model"),
                        "stage": trial.user_attrs.get("stage", current_stage),
                    }
                )

        rounds_summary = [
            {
                "iteration": trial.number + 1,
                "parameters": trial.user_attrs.get("parameters", {}),
                "score": float(trial.value) if trial.value is not None else None,
                "model": trial.user_attrs.get("model"),
                "stage": trial.user_attrs.get("stage"),
            }
            for trial in completed_trials
        ]

        try:
            importance = optuna_importance.get_param_importances(study)
        except (ValueError, RuntimeError, ImportError):
            # Falls back to custom sensitivity analysis if:
            # - Study has insufficient data (ValueError/RuntimeError)
            # - scikit-learn not installed (ImportError)
            importance = {}

        if not importance or all(value == 0 for value in importance.values()):
            importance = compute_sensitivity_from_trials(
                completed_trials, parameter_space.parameters
            )

        # Display final results
        reporting.display_result(
            initial_score=baseline_score,
            best_score=best_score,
            best_prompt=prompt.get_messages(),
            verbose=self.verbose,
            tools=getattr(prompt, "tools", None),
        )

        # Update optimization status to completed
        try:
            optimization.update(status="completed")
            logger.info(f"Optimization {optimization.id} status updated to completed.")
        except Exception as e:
            logger.warning(f"Failed to update optimization status: {e}")

        details = {
            "initial_score": baseline_score,
            "optimized_parameters": best_parameters,
            "optimized_model_kwargs": best_model_kwargs,
            "optimized_model": best_model,
            "trials": history,
            "parameter_space": parameter_space.model_dump(by_alias=True),
            "n_trials": total_trials,
            "model": best_model,
            "rounds": rounds_summary,
            "baseline_parameters": base_model_kwargs,
            "temperature": best_model_kwargs.get("temperature"),
            "local_trials": local_trials,
            "global_trials": global_trials,
            "search_stages": stage_records,
            "search_ranges": search_ranges,
            "parameter_importance": importance,
            "parameter_precision": 6,
        }

        return OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=prompt.get_messages() if hasattr(prompt, "get_messages") else [],
            initial_prompt=prompt.get_messages()
            if hasattr(prompt, "get_messages")
            else [],
            initial_score=baseline_score,
            score=best_score,
            metric_name=metric_name,
            details=details,
            history=history,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
            optimization_id=optimization.id,
            dataset_id=dataset.id,
        )
