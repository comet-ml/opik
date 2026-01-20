"""Shared Optuna helpers for optimizer implementations."""

from __future__ import annotations

from datetime import datetime, timezone
from typing import Any
from collections.abc import Callable
import logging

from optuna.trial import FrozenTrial, TrialState

from ..core import runtime


def configure_optuna_logging(
    *,
    logger: logging.Logger,
    level: int | None = None,
) -> None:
    """Disable Optuna default handler and align its log level with the SDK."""
    try:
        import optuna

        optuna.logging.disable_default_handler()
        optuna_logger = logging.getLogger("optuna")
        optuna_logger.setLevel(level if level is not None else logger.getEffectiveLevel())
        optuna_logger.propagate = False
    except Exception as exc:  # pragma: no cover - defensive safety
        logger.warning("Could not configure Optuna logging: %s", exc)


def extract_optuna_metadata(study: Any) -> dict[str, Any]:
    """Extract lightweight metadata about the Optuna study."""
    sampler_info = type(study.sampler).__name__ if study.sampler else None
    pruner_info = type(study.pruner).__name__ if study.pruner else None
    direction = study.direction.name if study.direction else None
    return {
        "sampler": sampler_info,
        "pruner": pruner_info,
        "study_direction": direction,
    }


def _default_trial_timestamp(trial: FrozenTrial) -> str:
    timestamp_source = (
        trial.datetime_complete
        or trial.datetime_start
        or datetime.now(timezone.utc)
    )
    return timestamp_source.isoformat()


def build_optuna_round(
    *,
    optimizer: Any,
    context: Any,
    trial: FrozenTrial,
    prompt_or_payload: Any,
    score: float | None,
    extra: dict[str, Any] | None,
    timestamp: str,
    round_meta: dict[str, Any] | None = None,
    candidate_id: str | None = None,
    post_extras: dict[str, Any] | None = None,
    post_metrics: dict[str, Any] | None = None,
) -> None:
    """Create a history round entry for an Optuna trial."""
    round_handle = optimizer.pre_round(context, **(round_meta or {}))
    runtime.record_and_post_trial(
        optimizer=optimizer,
        context=context,
        prompt_or_payload=prompt_or_payload,
        score=score,
        candidate_id=candidate_id or f"trial{trial.number}",
        extra=extra,
        round_handle=round_handle,
        timestamp=timestamp,
        post_extras=post_extras,
        post_metrics=post_metrics,
    )
    optimizer.post_round(
        round_handle=round_handle,
        context=context,
        stop_reason=context.finish_reason,
    )


def record_optuna_trial_history(
    *,
    optimizer: Any,
    context: Any,
    study: Any,
    build_prompt_payload: Callable[[FrozenTrial], Any],
    build_extra: Callable[[FrozenTrial], dict[str, Any] | None],
    build_round_meta: Callable[[FrozenTrial], dict[str, Any]] | None = None,
    timestamp_provider: Callable[[FrozenTrial], str] | None = None,
    on_skip: Callable[[FrozenTrial], None] | None = None,
    post_extras: dict[str, Any] | None = None,
    post_metrics: dict[str, Any] | None = None,
) -> None:
    """Record completed Optuna trials into optimizer history."""
    get_timestamp = timestamp_provider or _default_trial_timestamp
    for trial in study.trials:
        if trial.state != TrialState.COMPLETE or trial.value is None:
            if on_skip is not None:
                on_skip(trial)
            continue
        round_meta = build_round_meta(trial) if build_round_meta else {}
        build_optuna_round(
            optimizer=optimizer,
            context=context,
            trial=trial,
            prompt_or_payload=build_prompt_payload(trial),
            score=float(trial.value) if trial.value is not None else None,
            extra=build_extra(trial),
            timestamp=get_timestamp(trial),
            round_meta=round_meta,
            post_extras=post_extras,
            post_metrics=post_metrics,
        )
