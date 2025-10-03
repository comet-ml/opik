"""Simple Optuna-based optimizer for model parameter tuning."""

from __future__ import annotations

from collections.abc import Callable, Mapping
from typing import Any

import copy
import logging
import math
from datetime import datetime

import optuna
from optuna import importance as optuna_importance
from optuna.trial import Trial, TrialState

from opik import Dataset

from .. import optimization_result
from ..base_optimizer import BaseOptimizer
from ..optimizable_agent import OptimizableAgent
from ..optimization_config import chat_prompt
from .search_space import ParameterSearchSpace, ParameterSpec, ParameterType

logger = logging.getLogger(__name__)


def _compute_sensitivity_from_trials(
    trials: list[Trial], specs: list[ParameterSpec]
) -> dict[str, float]:
    sensitivities: dict[str, float] = {}

    for spec in specs:
        param_name = spec.name
        values: list[float] = []
        scores: list[float] = []

        for trial in trials:
            if trial.value is None:
                continue

            raw_value = trial.params.get(param_name)
            if isinstance(raw_value, bool):
                processed = float(int(raw_value))
            elif isinstance(raw_value, (int, float)):
                processed = float(raw_value)
            else:
                continue

            values.append(processed)
            scores.append(float(trial.value))

        if len(values) < 2 or len(set(values)) == 1:
            sensitivities[param_name] = 0.0
            continue

        mean_val = sum(values) / len(values)
        mean_score = sum(scores) / len(scores)

        cov = sum((v - mean_val) * (s - mean_score) for v, s in zip(values, scores))
        var_val = sum((v - mean_val) ** 2 for v in values)
        var_score = sum((s - mean_score) ** 2 for s in scores)

        if var_val <= 0 or var_score <= 0:
            sensitivities[param_name] = 0.0
            continue

        corr = abs(cov) / math.sqrt(var_val * var_score)
        sensitivities[param_name] = min(max(corr, 0.0), 1.0)

    return sensitivities


class ParameterOptimizer(BaseOptimizer):
    """Optimizer that tunes model call parameters (temperature, top_p, etc.)."""

    def __init__(
        self,
        model: str,
        *,
        default_n_trials: int = 20,
        n_threads: int = 4,
        seed: int = 42,
        verbose: int = 1,
        local_search_ratio: float = 0.3,
        local_search_scale: float = 0.2,
        **model_kwargs: Any,
    ) -> None:
        super().__init__(model=model, verbose=verbose, seed=seed, **model_kwargs)
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
        metric: Callable[[Any, Any], float],
        experiment_config: dict | None = None,
        n_samples: int | None = None,
        auto_continue: bool = False,
        agent_class: type[OptimizableAgent] | None = None,
        **kwargs: Any,
    ) -> optimization_result.OptimizationResult:
        raise NotImplementedError(
            "ParameterOptimizer.optimize_prompt is not supported. "
            "Call optimize_parameter with a ParameterSearchSpace instead."
        )

    def optimize_parameter(
        self,
        prompt: chat_prompt.ChatPrompt,
        dataset: Dataset,
        metric: Callable[[Any, Any], float],
        parameter_space: ParameterSearchSpace | Mapping[str, Any],
        experiment_config: dict | None = None,
        n_trials: int | None = None,
        n_samples: int | None = None,
        agent_class: type[OptimizableAgent] | None = None,
        **kwargs: Any,
    ) -> optimization_result.OptimizationResult:
        if not isinstance(parameter_space, ParameterSearchSpace):
            parameter_space = ParameterSearchSpace.model_validate(parameter_space)

        sampler = kwargs.pop("sampler", None)
        callbacks = kwargs.pop("callbacks", None)
        timeout = kwargs.pop("timeout", None)
        local_trials_override = kwargs.pop("local_trials", None)
        local_search_scale_override = kwargs.pop("local_search_scale", None)
        if kwargs:
            extra_keys = ", ".join(sorted(kwargs.keys()))
            raise TypeError(f"Unsupported keyword arguments: {extra_keys}")

        self.validate_optimization_inputs(prompt, dataset, metric)
        self.configure_prompt_model(prompt)

        base_model_kwargs = copy.deepcopy(prompt.model_kwargs or {})
        base_prompt = prompt.copy()
        base_prompt.model_kwargs = copy.deepcopy(base_model_kwargs)

        metric_name = getattr(metric, "__name__", str(metric))

        self.agent_class = self.setup_agent_class(base_prompt, agent_class)
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

        total_trials = self.default_n_trials if n_trials is None else n_trials
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

        def objective(trial: Trial) -> float:
            sampled_values = current_space.suggest(trial)
            tuned_prompt = parameter_space.apply(
                prompt,
                sampled_values,
                base_model_kwargs=base_model_kwargs,
            )
            tuned_agent_class = self.setup_agent_class(tuned_prompt, agent_class)
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
            trial.set_user_attr("parameters", sampled_values)
            trial.set_user_attr("model_kwargs", copy.deepcopy(tuned_prompt.model_kwargs))
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
        except (ValueError, RuntimeError):
            importance = {}

        if not importance or all(value == 0 for value in importance.values()):
            importance = _compute_sensitivity_from_trials(
                completed_trials, parameter_space.parameters
            )

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

        return optimization_result.OptimizationResult(
            optimizer=self.__class__.__name__,
            prompt=prompt.get_messages() if hasattr(prompt, "get_messages") else [],
            initial_prompt=prompt.get_messages() if hasattr(prompt, "get_messages") else [],
            initial_score=baseline_score,
            score=best_score,
            metric_name=metric_name,
            details=details,
            history=history,
            llm_calls=self.llm_call_counter,
            tool_calls=self.tool_call_counter,
        )
