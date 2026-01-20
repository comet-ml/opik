"""Shared Optuna operations for the parameter optimizer."""

from __future__ import annotations

from collections.abc import Callable
from datetime import datetime, timezone
from typing import Any, TYPE_CHECKING
import copy
import logging

import optuna
from optuna import importance as optuna_importance
from optuna.trial import Trial, TrialState

from opik import Dataset

from ....api_objects import chat_prompt
from ....api_objects.types import MetricFunction
from ....agents import OptimizableAgent
from ....core import runtime
from ....core.state import OptimizationContext
from ....utils import display as display_utils
from ....utils.logging import debug_log
from .search_ops import ParameterSearchSpace
from .sensitivity_ops import sensitivity_analysis
from .. import reporting

if TYPE_CHECKING:  # pragma: no cover - typing only
    from ..parameter_optimizer import ParameterOptimizer

logger = logging.getLogger(__name__)


def build_optuna_objective(
    *,
    optimizer: "ParameterOptimizer",
    context: OptimizationContext,
    current_space_ref: dict[str, ParameterSearchSpace],
    stage_ref: dict[str, Any],
    stage_counts: dict[str, int],
    best_state: dict[str, Any],
    base_prompts: dict[str, chat_prompt.ChatPrompt],
    base_model_kwargs: dict[str, Any],
    evaluation_dataset: Dataset,
    metric: MetricFunction,
    agent: OptimizableAgent | None,
    experiment_config: dict[str, Any] | None,
    n_samples: int | None,
    total_trials: int,
) -> Callable[[Trial], float]:
    def objective(trial: Trial) -> float:
        sampled_values = current_space_ref["space"].suggest(trial)

        tuned_prompts = current_space_ref["space"].apply_to_prompts(
            base_prompts,
            sampled_values,
            base_model_kwargs=base_model_kwargs,
        )

        with reporting.display_trial_evaluation(
            trial_number=trial.number,
            total_trials=total_trials,
            stage=stage_ref["name"],
            parameters=sampled_values,
            verbose=optimizer.verbose,
            selection_summary=display_utils.summarize_selection_policy(tuned_prompts),
        ) as trial_reporter:
            optimizer._set_reporter(trial_reporter)
            debug_log(
                "trial_start",
                trial_index=trial.number + 1,
                trials_completed=context.trials_completed,
                max_trials=total_trials,
            )
            stage = stage_ref["name"]
            stage_counts[stage] = stage_counts.get(stage, 0) + 1
            round_handle = optimizer.pre_round(
                context,
                stage=stage,
                type=stage_ref.get("type"),
                stage_count=stage_counts[stage],
                local_search_scale=stage_ref.get("local_search_scale"),
                local_trials=stage_ref.get("local_trials"),
                global_trials=stage_ref.get("global_trials"),
            )
            optimizer.pre_trial(context, tuned_prompts, round_handle=round_handle)
            score = optimizer.evaluate_prompt(
                prompt=tuned_prompts,
                agent=agent,
                dataset=evaluation_dataset,
                metric=metric,
                n_threads=optimizer.n_threads,
                verbose=optimizer.verbose,
                experiment_config=experiment_config,
                n_samples=n_samples,
            )

            prev_best_score = best_state["score"]
            if score > best_state["score"]:
                best_state["score"] = score
                best_state["prompts"] = copy.deepcopy(tuned_prompts)

            trial_reporter.set_score(score, prev_best_score)
            optimizer._clear_reporter()
            debug_log(
                "trial_end",
                trial_index=trial.number + 1,
                score=score,
                trials_completed=context.trials_completed,
            )

        trial.set_user_attr("parameters", sampled_values)
        model_kwargs_for_trial = {
            name: copy.deepcopy(p.model_kwargs or {})
            for name, p in tuned_prompts.items()
        }
        trial.set_user_attr("model_kwargs", model_kwargs_for_trial)
        trial.set_user_attr("model", {name: p.model for name, p in tuned_prompts.items()})
        trial.set_user_attr("stage", stage)
        if stage_ref.get("type") is not None:
            trial.set_user_attr("type", stage_ref.get("type"))
        if stage_ref.get("local_search_scale") is not None:
            trial.set_user_attr("local_search_scale", stage_ref.get("local_search_scale"))
        if stage_ref.get("local_trials") is not None:
            trial.set_user_attr("local_trials", stage_ref.get("local_trials"))
        if stage_ref.get("global_trials") is not None:
            trial.set_user_attr("global_trials", stage_ref.get("global_trials"))

        runtime.record_and_post_trial(
            optimizer=optimizer,
            context=context,
            prompt_or_payload=tuned_prompts,
            score=score,
            candidate_id=f"trial{trial.number}",
            extra={
                "parameters": sampled_values,
                "model": trial.user_attrs.get("model"),
                "stage": stage,
                "type": stage_ref.get("type"),
            },
            round_handle=round_handle,
            timestamp=datetime.now(timezone.utc).isoformat(),
            post_extras={
                "parameters": sampled_values,
                "model": trial.user_attrs.get("model"),
                "stage": stage,
                "type": stage_ref.get("type"),
            },
        )
        optimizer.post_round(
            round_handle=round_handle,
            context=context,
            stop_reason=context.finish_reason,
        )
        return float(score)

    return objective


def run_optuna_phase(
    *,
    optimizer: "ParameterOptimizer",
    study: optuna.study.Study,
    objective: Callable[[Trial], float],
    n_trials: int,
    timeout: float | None,
    callbacks: list[Callable[[optuna.study.Study, optuna.trial.FrozenTrial], None]]
    | None,
    stage_name: str,
    space: ParameterSearchSpace,
    stage_ref: dict[str, Any],
    current_space_ref: dict[str, ParameterSearchSpace],
    stage_records: list[dict[str, Any]],
    search_ranges: dict[str, dict[str, Any]],
    scale: float | None = None,
    local_trials: int | None = None,
    global_trials: int | None = None,
    display_title: str | None = None,
    display_description: str | None = None,
) -> None:
    if n_trials <= 0:
        return
    stage_ref["name"] = stage_name
    stage_ref["type"] = stage_name
    stage_ref["local_search_scale"] = scale
    stage_ref["local_trials"] = local_trials
    stage_ref["global_trials"] = global_trials
    current_space_ref["space"] = space
    stage_range = space.describe()
    stage_records.append(
        {
            "stage": stage_name,
            "trials": n_trials,
            "scale": scale if scale is not None else 1.0,
            "parameters": stage_range,
        }
    )
    search_ranges[stage_name] = stage_range

    if optimizer.verbose >= 1 and display_title:
        from rich.text import Text
        from rich.console import Console

        console = Console()
        console.print("")
        console.print(Text(display_title, style="bold cyan"))
        if display_description:
            console.print(Text(display_description))
        console.print("")

    study.optimize(
        objective,
        n_trials=n_trials,
        timeout=timeout,
        callbacks=callbacks,
        show_progress_bar=False,
    )


def completed_trials(study: optuna.study.Study) -> list[optuna.trial.FrozenTrial]:
    return [
        trial
        for trial in study.trials
        if trial.state == TrialState.COMPLETE and trial.value is not None
    ]


def select_best_trial(
    *,
    completed_trials: list[optuna.trial.FrozenTrial],
    best_score: float,
    best_parameters: dict[str, Any],
    best_model_kwargs: dict[str, Any],
    best_model: dict[str, str],
) -> tuple[float, dict[str, Any], dict[str, Any], dict[str, str]]:
    if not completed_trials:
        return best_score, best_parameters, best_model_kwargs, best_model
    best_trial = max(completed_trials, key=lambda t: t.value)  # type: ignore[arg-type]
    if best_trial.value is not None and best_trial.value > best_score:
        best_score = float(best_trial.value)
        best_parameters = best_trial.user_attrs.get("parameters", {})
        best_model_kwargs = best_trial.user_attrs.get("model_kwargs", {})
        best_model = best_trial.user_attrs.get("model", best_model)
    return best_score, best_parameters, best_model_kwargs, best_model


def compute_parameter_importance(
    *,
    study: optuna.study.Study,
    completed_trials: list[optuna.trial.FrozenTrial],
    expanded_parameter_space: ParameterSearchSpace,
) -> dict[str, float]:
    try:
        importance = optuna_importance.get_param_importances(study)
    except (ValueError, RuntimeError, ImportError):
        importance = {}

    if not importance or all(value == 0 for value in importance.values()):
        return sensitivity_analysis(
            completed_trials, expanded_parameter_space.parameters
        )
    return importance


def update_optimization_status(
    *,
    optimizer: "ParameterOptimizer",
    optimization: Any | None,
) -> None:
    if optimization is None:
        return
    count = 0
    while count < 3:
        try:
            optimization.update(status="completed")
            logger.info(
                "Optimization %s status updated to completed.",
                optimizer.current_optimization_id,
            )
            break
        except Exception:
            count += 1
            import time

            time.sleep(5)
    if count == 3:
        logger.warning("Unable to update optimization status; continuing...")
