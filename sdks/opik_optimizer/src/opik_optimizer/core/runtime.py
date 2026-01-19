from __future__ import annotations

from typing import Any, TYPE_CHECKING, cast
from collections.abc import Callable
import json
from collections.abc import Sequence
import copy
import inspect
import logging
import math

from opik import Dataset

from ..api_objects import chat_prompt
from ..api_objects.types import MetricFunction
from ..agents import LiteLLMAgent, OptimizableAgent
from ..core.results import OptimizationResult, OptimizationRound
from ..core.state import AlgorithmResult, OptimizationContext
from ..utils.logging import debug_log
from .. import helpers

if TYPE_CHECKING:  # pragma: no cover
    from ..base_optimizer import BaseOptimizer


logger = logging.getLogger(__name__)


def deep_merge_dicts(base: dict[str, Any], overrides: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(base)
    for key, value in overrides.items():
        if key in result and isinstance(result[key], dict) and isinstance(value, dict):
            result[key] = deep_merge_dicts(result[key], value)
        else:
            result[key] = value
    return result


def prepare_experiment_config(
    *,
    optimizer: BaseOptimizer,
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    dataset: Dataset,
    metric: MetricFunction,
    agent: OptimizableAgent | None,
    validation_dataset: Dataset | None,
    experiment_config: dict[str, Any] | None,
    configuration_updates: dict[str, Any] | None,
    additional_metadata: dict[str, Any] | None,
    is_single_prompt_optimization: bool,
) -> dict[str, Any]:
    project_name = optimizer.project_name

    prompt_messages: list[dict[str, Any]] | dict[str, list[dict[str, Any]]]
    prompt_name: str | None | dict[str, str | None]
    prompt_project_name: str | None | dict[str, str | None]

    if isinstance(prompt, dict):
        first_prompt = next(iter(prompt.values()))
        agent_config = optimizer._build_agent_config(first_prompt)
        tool_signatures = optimizer._summarize_tool_signatures(first_prompt)

        if is_single_prompt_optimization:
            prompt_messages = first_prompt.get_messages()
            prompt_name = getattr(first_prompt, "name", None)
            prompt_project_name = getattr(first_prompt, "project_name", None)
        else:
            prompt_dict = cast(dict[str, chat_prompt.ChatPrompt], prompt)
            prompt_messages = {k: p.get_messages() for k, p in prompt_dict.items()}
            prompt_name = {k: getattr(p, "name", None) for k, p in prompt_dict.items()}
            prompt_project_name = {
                k: getattr(p, "project_name", None) for k, p in prompt_dict.items()
            }

        tools = optimizer._serialize_tools(first_prompt)
    else:
        agent_config = optimizer._build_agent_config(prompt)
        tool_signatures = optimizer._summarize_tool_signatures(prompt)
        prompt_messages = prompt.get_messages()
        prompt_name = getattr(prompt, "name", None)
        tools = optimizer._serialize_tools(prompt)
        prompt_project_name = getattr(prompt, "project_name", None)

    base_config: dict[str, Any] = {
        "project_name": project_name,
        "agent_config": agent_config,
        "metric": metric.__name__,
        "dataset_training": dataset.name,
        "dataset_training_id": dataset.id,
        "optimizer": optimizer.__class__.__name__,
        "optimizer_metadata": optimizer._build_optimizer_metadata(),
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
        base_config["configuration"] = deep_merge_dicts(
            base_config["configuration"], configuration_updates
        )

    if additional_metadata:
        base_config = deep_merge_dicts(base_config, additional_metadata)

    if experiment_config:
        base_config = deep_merge_dicts(base_config, experiment_config)

    if validation_dataset:
        base_config["validation_dataset"] = validation_dataset.name
        base_config["validation_dataset_id"] = validation_dataset.id

    return helpers.drop_none(base_config)


def select_result_prompts(
    *,
    best_prompts: dict[str, chat_prompt.ChatPrompt],
    initial_prompts: dict[str, chat_prompt.ChatPrompt],
    is_single_prompt_optimization: bool,
) -> tuple[Any, Any]:
    if is_single_prompt_optimization:
        return list(best_prompts.values())[0], list(initial_prompts.values())[0]
    return best_prompts, initial_prompts


def build_early_result(**kwargs: Any) -> OptimizationResult:
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
        llm_calls=kwargs.get("llm_calls"),
        llm_calls_tools=kwargs.get("llm_calls_tools"),
        llm_cost_total=kwargs.get("llm_cost_total"),
        llm_token_usage_total=kwargs.get("llm_token_usage_total"),
        dataset_id=kwargs.get("dataset_id"),
        optimization_id=kwargs.get("optimization_id"),
    )


def build_final_result(
    *,
    optimizer: BaseOptimizer,
    algorithm_result: AlgorithmResult,
    context: OptimizationContext,
) -> OptimizationResult:
    result_prompt, initial_prompt = select_result_prompts(
        best_prompts=algorithm_result.best_prompts,
        initial_prompts=context.initial_prompts,
        is_single_prompt_optimization=context.is_single_prompt_optimization,
    )

    optimizer_metadata = optimizer.get_metadata(context)
    finish_reason = context.finish_reason or "completed"
    stopped_early = finish_reason != "completed"

    details = {
        "initial_score": context.baseline_score,
        "model": optimizer.model,
        "temperature": optimizer.model_parameters.get("temperature"),
        "model_parameters": dict(optimizer.model_parameters),
        "trials_completed": context.trials_completed,
        "finish_reason": finish_reason,
        "stopped_early": stopped_early,
        "stop_reason": finish_reason,
        "verbose": optimizer.verbose,
    }

    details.update(optimizer_metadata)
    details.update(algorithm_result.metadata)

    history_entries = _coerce_history_entries(algorithm_result.history)
    if not history_entries:
        history_entries = optimizer._history_builder.get_entries()
    if not history_entries:
        history_entries = _record_fallback_history(
            optimizer=optimizer,
            result_prompt=result_prompt,
            score=algorithm_result.best_score,
            finish_reason=finish_reason,
        )

    return OptimizationResult(
        optimizer=optimizer.__class__.__name__,
        prompt=result_prompt,
        score=algorithm_result.best_score,
        metric_name=context.metric.__name__,
        initial_prompt=initial_prompt,
        initial_score=context.baseline_score,
        details=details,
        history=history_entries,
        llm_calls=optimizer.llm_call_counter,
        llm_calls_tools=optimizer.llm_call_tools_counter,
        dataset_id=context.dataset.id,
        optimization_id=context.optimization_id,
    )


def _coerce_history_entries(
    history: Sequence[dict[str, Any] | OptimizationRound],
) -> list[dict[str, Any]]:
    entries: list[dict[str, Any]] = []
    for entry in history:
        if hasattr(entry, "to_dict"):
            entries.append(entry.to_dict())
        elif isinstance(entry, dict):
            entries.append(entry)
    return entries


def _record_fallback_history(
    *,
    optimizer: BaseOptimizer,
    result_prompt: Any,
    score: float,
    finish_reason: str,
) -> list[dict[str, Any]]:
    fallback_round = optimizer.begin_round()
    entry = optimizer.record_candidate_entry(
        prompt_or_payload=result_prompt,
        score=score,
        id="fallback",
    )
    optimizer.post_candidate(
        result_prompt,
        score=score,
        candidates=[entry],
        round_handle=fallback_round,
    )
    optimizer.post_round(
        fallback_round,
        best_score=score,
        best_prompt=result_prompt,
        stop_reason=finish_reason,
    )
    return optimizer._history_builder.get_entries()


def setup_agent_class(
    optimizer: BaseOptimizer,
    prompt: chat_prompt.ChatPrompt,
    agent_class: Any = None,
) -> Any:
    if agent_class is None:
        return LiteLLMAgent
    if not issubclass(agent_class, OptimizableAgent):
        raise TypeError(
            f"agent_class must inherit from OptimizableAgent, got {agent_class.__name__}"
        )
    return agent_class


def bind_optimizer(
    optimizer: BaseOptimizer, agent: OptimizableAgent
) -> OptimizableAgent:
    try:
        agent.optimizer = optimizer  # type: ignore[attr-defined]
    except Exception:  # pragma: no cover - custom agents may forbid new attrs
        logger.debug(
            "Unable to record optimizer on agent instance of %s",
            agent.__class__.__name__,
        )
    return agent


def instantiate_agent(
    optimizer: BaseOptimizer,
    *args: Any,
    agent_class: type[OptimizableAgent] | None = None,
    **kwargs: Any,
) -> OptimizableAgent:
    resolved_class = agent_class or getattr(optimizer, "agent_class", None)
    if resolved_class is None:
        raise ValueError("agent_class must be provided before instantiation")
    agent = resolved_class(*args, **kwargs)
    return bind_optimizer(optimizer, agent)


def extract_tool_prompts(
    tools: list[dict[str, Any]] | None,
) -> dict[str, str] | None:
    if not tools:
        return None
    return {
        (tool.get("function", {}).get("name") or f"tool_{idx}"): tool.get(
            "function", {}
        ).get("description", "")
        for idx, tool in enumerate(tools)
    }


def serialize_tools(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
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


def describe_annotation(annotation: Any) -> str | None:
    if annotation is inspect._empty:
        return None
    if isinstance(annotation, type):
        return annotation.__name__
    return str(annotation)


def summarize_tool_signatures(prompt: chat_prompt.ChatPrompt) -> list[dict[str, Any]]:
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
                        "annotation": describe_annotation(parameter.annotation),
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


def build_agent_config(
    *,
    optimizer: BaseOptimizer,
    prompt: chat_prompt.ChatPrompt,
) -> dict[str, Any]:
    agent_config: dict[str, Any] = dict(prompt.to_dict())
    agent_config["project_name"] = getattr(prompt, "project_name", None)
    agent_config["model"] = getattr(prompt, "model", None) or optimizer.model
    agent_config["tools"] = serialize_tools(prompt)
    agent_config["optimizer"] = optimizer.__class__.__name__
    return helpers.drop_none(agent_config)


def evaluation_progress(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    prompts: dict[str, chat_prompt.ChatPrompt],
    score: float,
    prev_best_score: float | None,
) -> None:
    optimizer._display.evaluation_progress(
        context=context,
        prompts=prompts,
        score=coerce_score(score),
        display_info={"prev_best_score": prev_best_score},
    )


def show_header(*, optimizer: BaseOptimizer, context: OptimizationContext) -> None:
    optimizer._display.show_header(
        algorithm=optimizer.__class__.__name__,
        optimization_id=context.optimization_id,
        dataset_id=context.dataset.id,
    )


def show_configuration(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
) -> None:
    optimizer._display.show_configuration(
        prompt=prompt,
        optimizer_config=optimizer.get_config(context),
    )


def show_final_result(
    *,
    optimizer: BaseOptimizer,
    initial_score: float,
    best_score: float,
    prompt: Any,
) -> None:
    optimizer._display.show_final_result(
        initial_score=initial_score,
        best_score=best_score,
        prompt=prompt,
    )


def show_run_start(*, optimizer: BaseOptimizer, context: OptimizationContext) -> None:
    show_header(optimizer=optimizer, context=context)
    display_prompt = select_display_prompt(
        prompts=context.prompts,
        is_single_prompt_optimization=context.is_single_prompt_optimization,
    )
    show_configuration(
        optimizer=optimizer,
        context=context,
        prompt=display_prompt,
    )


def select_display_prompt(
    *,
    prompts: dict[str, chat_prompt.ChatPrompt],
    is_single_prompt_optimization: bool,
) -> chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt]:
    if is_single_prompt_optimization:
        return list(prompts.values())[0]
    return prompts


def select_result_display_prompt(result_prompt: Any) -> Any:
    return result_prompt


def run_baseline_evaluation(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    evaluate_fn: Callable[[], float],
) -> float:
    debug_log(
        "baseline_start",
        dataset=getattr(context.evaluation_dataset, "name", None),
        max_trials=context.max_trials,
        n_samples=context.n_samples,
        n_threads=getattr(optimizer, "n_threads", None),
    )
    with optimizer._display.baseline_evaluation(context) as baseline_reporter:
        baseline_score = evaluate_fn()
        baseline_reporter.set_score(baseline_score)
        debug_log("baseline_end", score=baseline_score)
    return baseline_score


def record_baseline_history(
    *,
    optimizer: BaseOptimizer,
    prompt: Any,
    score: float,
    stop_reason: str | None,
) -> None:
    optimizer._history_builder.clear()
    baseline_round = optimizer.begin_round()
    entry = optimizer.record_candidate_entry(
        prompt_or_payload=prompt,
        score=score,
        id="baseline",
    )
    optimizer.post_candidate(
        prompt,
        score=score,
        candidates=[entry],
        round_handle=baseline_round,
    )
    optimizer.post_round(
        baseline_round,
        best_score=score,
        best_prompt=prompt,
        stop_reason=stop_reason if isinstance(stop_reason, str) else None,
    )


def build_early_stop_details(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    baseline_score: float,
) -> dict[str, Any]:
    optimizer_metadata = optimizer.get_metadata(context)
    early_stop_details = {
        "initial_score": baseline_score,
        "stopped_early": True,
        "stop_reason": "baseline_score_met_threshold",
        "stop_reason_details": {"best_score": baseline_score},
        "perfect_score": optimizer.perfect_score,
        "skip_perfect_score": optimizer.skip_perfect_score,
        "model": optimizer.model,
        "temperature": optimizer.model_parameters.get("temperature"),
    }
    early_stop_details.update(optimizer_metadata)
    early_stop_details.pop("rounds_completed", None)
    if early_stop_details.get("trials_completed", 0) == 0:
        early_stop_details["trials_completed"] = 1
    return early_stop_details


def build_early_stop_result(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    baseline_score: float,
    prompt: Any,
    initial_prompt: Any,
    details: dict[str, Any],
) -> OptimizationResult:
    return optimizer._build_early_result(
        optimizer_name=optimizer.__class__.__name__,
        prompt=prompt,
        initial_prompt=initial_prompt,
        score=baseline_score,
        metric_name=context.metric.__name__,
        details=details,
        history=optimizer._history_builder.get_entries(),
        llm_calls=optimizer.llm_call_counter,
        llm_calls_tools=optimizer.llm_call_tools_counter,
        dataset_id=context.dataset.id,
        optimization_id=context.optimization_id,
    )


def log_final_state(*, optimizer: BaseOptimizer, result: OptimizationResult) -> None:
    debug_logger = logging.getLogger("opik_optimizer.debug")
    if not debug_logger.isEnabledFor(logging.DEBUG):
        return
    try:
        details_text = json.dumps(result.details, default=str, indent=2, sort_keys=True)
        history_text = json.dumps(
            optimizer._history_builder.get_entries(),
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
            optimizer._history_builder.get_entries(),
        )


def validate_inputs(
    *,
    prompt: chat_prompt.ChatPrompt | dict[str, chat_prompt.ChatPrompt],
    dataset: Dataset,
    metric: MetricFunction,
    support_content_parts: bool,
) -> None:
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
        raise ValueError(
            "Prompt must be a ChatPrompt object or a dictionary of ChatPrompt objects"
        )

    if not isinstance(dataset, Dataset):
        raise ValueError("Dataset must be a Dataset object")

    if not callable(metric):
        raise ValueError(
            "Metric must be a callable function that takes `dataset_item` and `llm_output` as arguments, "
            "and optionally `task_span` for metrics that need access to span information."
        )


def cleanup(optimizer: BaseOptimizer) -> None:
    optimizer._reset_counters()
    optimizer._history_builder.clear()
    if optimizer._opik_client is not None:
        optimizer._opik_client = None
    optimizer._reporter = None
    logger.debug("Cleaned up resources for %s", optimizer.__class__.__name__)


def get_opik_client(optimizer: BaseOptimizer) -> Any:
    if optimizer._opik_client is None:
        import opik

        optimizer._opik_client = opik.Opik()
    return optimizer._opik_client


def should_stop(optimizer: BaseOptimizer, context: OptimizationContext) -> bool:
    if context.should_stop:
        return True

    if optimizer.skip_perfect_score and context.current_best_score is not None:
        if context.current_best_score >= optimizer.perfect_score:
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


def should_skip_optimization(
    *,
    optimizer: BaseOptimizer,
    baseline_score: float | None,
    skip_perfect_score: bool | None,
    perfect_score: float | None,
) -> bool:
    if baseline_score is None:
        return False
    effective_skip = (
        optimizer.skip_perfect_score
        if skip_perfect_score is None
        else skip_perfect_score
    )
    if not effective_skip:
        return False
    threshold = optimizer.perfect_score if perfect_score is None else perfect_score
    return baseline_score >= threshold


def finalize_finish_reason(context: OptimizationContext) -> None:
    if context.finish_reason is None:
        if context.trials_completed >= context.max_trials:
            context.finish_reason = "max_trials"
        else:
            context.finish_reason = "completed"


def reset_usage(optimizer: BaseOptimizer) -> None:
    optimizer.llm_call_counter = 0
    optimizer.llm_call_tools_counter = 0
    optimizer.llm_cost_total = 0.0
    optimizer.llm_token_usage_total = {
        "prompt_tokens": 0,
        "completion_tokens": 0,
        "total_tokens": 0,
    }


def increment_llm_call(optimizer: BaseOptimizer) -> None:
    optimizer.llm_call_counter += 1


def increment_llm_tool_call(optimizer: BaseOptimizer) -> None:
    optimizer.llm_call_tools_counter += 1


def add_llm_cost(optimizer: BaseOptimizer, cost: float | None) -> None:
    if cost is None:
        return
    optimizer.llm_cost_total += float(cost)


def add_llm_usage(optimizer: BaseOptimizer, usage: dict[str, Any] | None) -> None:
    if not usage:
        return
    optimizer.llm_token_usage_total["prompt_tokens"] += int(
        usage.get("prompt_tokens", 0)
    )
    optimizer.llm_token_usage_total["completion_tokens"] += int(
        usage.get("completion_tokens", 0)
    )
    optimizer.llm_token_usage_total["total_tokens"] += int(usage.get("total_tokens", 0))


def coerce_score(raw_score: Any) -> float:
    try:
        score = float(raw_score)
    except (TypeError, ValueError):
        raise TypeError(
            f"Score must be convertible to float, got {type(raw_score).__name__}"
        )

    if math.isnan(score):
        raise ValueError("Score cannot be NaN.")

    return score
