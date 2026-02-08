from __future__ import annotations

from typing import Any, TYPE_CHECKING, cast
from collections.abc import Callable, Sequence
from contextlib import contextmanager
import json
import logging
import math
import os
import gc
import signal
import threading
import warnings
import multiprocessing.resource_tracker

from opik import Dataset

from ..api_objects import chat_prompt
from ..api_objects.types import MetricFunction
from ..core.results import OptimizationResult, OptimizationRound
from ..core.state import AlgorithmResult, OptimizationContext
from ..utils.logging import debug_log

if TYPE_CHECKING:  # pragma: no cover
    from ..base_optimizer import BaseOptimizer


logger = logging.getLogger(__name__)
_INHERIT = object()


@contextmanager
def handle_termination(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    timeout_sec: float = 2.0,
) -> Any:
    """Handle SIGINT/SIGTERM by marking the run cancelled and updating Opik."""
    handled = False
    previous_handlers: dict[int, Any] = {}

    def _handler(signum: int, _frame: Any) -> None:
        nonlocal handled
        if handled:
            return
        handled = True
        logger.warning("Received signal %s; cancelling optimization.", signum)
        context.should_stop = True
        context.finish_reason = "cancelled"

        def _finalize() -> None:
            try:
                optimizer._finalize_optimization(context, status="cancelled")
            except Exception:
                logger.exception(
                    "Failed to finalize optimization after signal %s", signum
                )

        finalize_thread = threading.Thread(target=_finalize, daemon=True)
        finalize_thread.start()
        finalize_thread.join(timeout=timeout_sec)
        warnings.filterwarnings(
            "ignore",
            message=r"resource_tracker: There appear to be .* leaked semaphore objects.*",
            module=r"multiprocessing\\.resource_tracker",
        )
        try:
            stopper = getattr(
                multiprocessing.resource_tracker._resource_tracker, "_stop", None
            )
            if callable(stopper):
                stopper()  # type: ignore[misc]
        except Exception:
            pass
        gc.collect()
        os._exit(128 + signum)

    signals: list[int] = []
    if hasattr(signal, "SIGTERM"):
        signals.append(signal.SIGTERM)
    if hasattr(signal, "SIGINT"):
        signals.append(signal.SIGINT)

    for sig in signals:
        previous_handlers[sig] = signal.getsignal(sig)
        signal.signal(sig, _handler)

    try:
        yield
    finally:
        for sig, prev in previous_handlers.items():
            signal.signal(sig, prev)


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
            context=context,
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
    context: OptimizationContext,
    result_prompt: Any,
    score: float,
    finish_reason: str,
) -> list[dict[str, Any]]:
    fallback_round = optimizer.pre_round(context)
    entry = optimizer.record_candidate_entry(
        prompt_or_payload=result_prompt,
        score=score,
        id="fallback",
        context=context,
    )
    optimizer.post_trial(
        context,
        result_prompt,
        score=score,
        candidates=[entry],
        round_handle=fallback_round,
    )
    optimizer.post_round(
        fallback_round,
        context=context,
        best_score=score,
        best_prompt=result_prompt,
        stop_reason=finish_reason,
    )
    return optimizer._history_builder.get_entries()


def record_and_post_trial(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    prompt_or_payload: Any,
    score: float | None,
    candidate_id: str | None = None,
    metrics: dict[str, Any] | None = None,
    notes: str | None = None,
    extra: dict[str, Any] | None = None,
    candidate_handle: Any | None = None,
    round_handle: Any | None = None,
    dataset: str | None = None,
    dataset_split: str | None = None,
    trial_index: int | None = None,
    timestamp: str | None = None,
    post_metrics: dict[str, Any] | None | object = _INHERIT,
    post_extras: dict[str, Any] | None | object = _INHERIT,
) -> dict[str, Any]:
    """
    Record a candidate entry and immediately post a trial.

    Use post_metrics/post_extras to control what is stored on the trial record:
    - _INHERIT (default) uses the candidate entry fields.
    - None forces no metrics/extras on the trial record.
    """
    entry = optimizer.record_candidate_entry(
        prompt_or_payload=prompt_or_payload,
        score=score,
        id=candidate_id,
        metrics=metrics,
        notes=notes,
        extra=extra,
        context=context,
    )
    if post_metrics is _INHERIT:
        resolved_metrics: dict[str, Any] | None = entry.get("metrics")
    else:
        resolved_metrics = cast(dict[str, Any] | None, post_metrics)
    if post_extras is _INHERIT:
        resolved_extras: dict[str, Any] | None = entry.get("extra")
    else:
        resolved_extras = cast(dict[str, Any] | None, post_extras)
    optimizer.post_trial(
        context,
        candidate_handle if candidate_handle is not None else prompt_or_payload,
        score=score,
        metrics=resolved_metrics,
        extras=resolved_extras,
        candidates=[entry],
        dataset=dataset,
        dataset_split=dataset_split,
        trial_index=trial_index,
        timestamp=timestamp,
        round_handle=round_handle,
    )
    return entry


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


def evaluation_progress(
    *,
    optimizer: BaseOptimizer,
    context: OptimizationContext,
    prompts: dict[str, chat_prompt.ChatPrompt],
    score: float,
    prev_best_score: float | None,
) -> None:
    if context.extra_params.get("suppress_evaluation_progress"):
        return
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
    optimizer_config = dict(optimizer.get_config(context))
    if "n_samples" not in optimizer_config and context.n_samples is not None:
        optimizer_config["n_samples"] = context.n_samples
    if (
        "n_samples_minibatch" not in optimizer_config
        and context.n_samples_minibatch is not None
    ):
        optimizer_config["n_samples_minibatch"] = context.n_samples_minibatch
    if "n_samples_strategy" not in optimizer_config and context.n_samples_strategy:
        optimizer_config["n_samples_strategy"] = context.n_samples_strategy
    optimizer_config.setdefault("allow_tool_use", context.allow_tool_use)
    optimizer_config.setdefault(
        "optimizable_roles", context.extra_params.get("optimizable_roles")
    )
    optimizer_config.setdefault(
        "optimize_tools", context.extra_params.get("optimize_tools")
    )
    optimizer_config.setdefault("tool_names", context.extra_params.get("tool_names"))

    optimizer._display.show_configuration(
        prompt=prompt,
        optimizer_config=optimizer_config,
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
        training_dataset=getattr(context.dataset, "name", None),
        validation_dataset=getattr(context.validation_dataset, "name", None),
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
    context: OptimizationContext,
    prompt: Any,
    score: float,
    stop_reason: str | None,
) -> None:
    optimizer._history_builder.clear()
    baseline_round = optimizer.pre_round(context)
    entry = optimizer.record_candidate_entry(
        prompt_or_payload=prompt,
        score=score,
        id="baseline",
        context=context,
    )
    optimizer.post_trial(
        context,
        prompt,
        score=score,
        candidates=[entry],
        round_handle=baseline_round,
    )
    optimizer.post_round(
        baseline_round,
        context=context,
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
        from ..utils.display import format as display_format

        def _redact_payload(value: Any) -> Any:
            if hasattr(value, "get_messages"):
                return display_format.redact_prompt_payload(value)
            if isinstance(value, dict):
                return {key: _redact_payload(val) for key, val in value.items()}
            if isinstance(value, list):
                return [_redact_payload(item) for item in value]
            return value

        redacted_details = _redact_payload(result.details)
        redacted_history = _redact_payload(optimizer._history_builder.get_entries())
        details_text = json.dumps(
            redacted_details, default=str, indent=2, sort_keys=True
        )
        history_text = json.dumps(
            redacted_history, default=str, indent=2, sort_keys=True
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
