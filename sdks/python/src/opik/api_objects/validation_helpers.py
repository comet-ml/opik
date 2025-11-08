import logging
from typing import Any, Dict, Optional, Tuple, Union, cast

from .. import dict_utils, llm_usage, logging_messages
from opik.types import LLMProvider
from ..types import FeedbackScoreDict
from ..validation import feedback_score as feedback_score_validator

_PASSTHROUGH_USAGE_KEYS = ("video_duration_seconds",)


def validate_and_parse_usage(
    usage: Any,
    logger: logging.Logger,
    provider: Optional[Union[LLMProvider, str]],
) -> Optional[Dict[str, int]]:
    backend_ready_usage = _maybe_return_backend_ready_usage(usage)
    if backend_ready_usage is not None:
        return backend_ready_usage

    passthrough_usage: Dict[str, int] = {}
    if isinstance(usage, dict):
        usage, passthrough_usage = _extract_passthrough_usage(usage)

    if isinstance(usage, llm_usage.OpikUsage):
        backend_usage = usage.to_backend_compatible_full_usage_dict()
        return _merge_passthrough_usage(backend_usage, passthrough_usage)

    if usage is None:
        return passthrough_usage or None

    unknown_provider = (provider is None) or (not LLMProvider.has_value(provider))

    if unknown_provider:
        backend_usage = llm_usage.build_opik_usage_from_unknown_provider(
            usage
        ).to_backend_compatible_full_usage_dict()
        return _merge_passthrough_usage(backend_usage, passthrough_usage)

    provider = LLMProvider(provider)

    try:
        opik_usage = llm_usage.build_opik_usage(provider=provider, usage=usage)
        return _merge_passthrough_usage(
            opik_usage.to_backend_compatible_full_usage_dict(), passthrough_usage
        )
    except Exception:
        backend_usage = llm_usage.build_opik_usage_from_unknown_provider(
            usage
        ).to_backend_compatible_full_usage_dict()
        return _merge_passthrough_usage(backend_usage, passthrough_usage)


def validate_feedback_score(
    feedback_score: Any, logger: logging.Logger
) -> Optional[FeedbackScoreDict]:
    feedback_score_validator_ = feedback_score_validator.FeedbackScoreValidator(
        feedback_score
    )

    if feedback_score_validator_.validate().failed():
        logger.warning(
            logging_messages.INVALID_FEEDBACK_SCORE_WILL_NOT_BE_LOGGED,
            feedback_score,
            feedback_score_validator_.failure_reason_message(),
        )
        return None

    return cast(FeedbackScoreDict, feedback_score)


def _maybe_return_backend_ready_usage(usage: Any) -> Optional[Dict[str, int]]:
    if not isinstance(usage, dict):
        return None

    if not usage:
        return {}

    for value in usage.values():
        if not isinstance(value, int):
            return None

    return dict_utils.keep_only_values_of_type(usage, int)


def _extract_passthrough_usage(usage: Dict[str, Any]) -> Tuple[Any, Dict[str, int]]:
    cleaned_usage: Any = usage
    passthrough: Dict[str, int] = {}

    if not isinstance(usage, dict):
        return cleaned_usage, passthrough

    cleaned_usage = dict(usage)
    for key in _PASSTHROUGH_USAGE_KEYS:
        if key not in cleaned_usage:
            continue
        parsed_value = _coerce_int(cleaned_usage.pop(key))
        if parsed_value is not None:
            passthrough[key] = parsed_value

    return cleaned_usage, passthrough


def _coerce_int(value: Any) -> Optional[int]:
    if isinstance(value, bool):
        return int(value)
    if isinstance(value, int):
        return value
    if isinstance(value, float):
        try:
            return int(value)
        except (ValueError, TypeError):
            return None
    if isinstance(value, str):
        try:
            return int(value)
        except ValueError:
            return None
    return None


def _merge_passthrough_usage(
    backend_usage: Optional[Dict[str, int]], passthrough_usage: Dict[str, int]
) -> Optional[Dict[str, int]]:
    if not passthrough_usage:
        return backend_usage
    if backend_usage is None:
        return dict(passthrough_usage)
    merged = dict(backend_usage)
    merged.update(passthrough_usage)
    return merged
