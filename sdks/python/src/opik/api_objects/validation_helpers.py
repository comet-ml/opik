import logging
from typing import Any, Optional, cast, Union, Dict

from ..types import BatchFeedbackScoreDict
from ..validation import feedback_score as feedback_score_validator
from .. import logging_messages, llm_usage
from opik.types import LLMProvider


def _is_already_backend_format(usage: Dict[str, Any]) -> bool:
    """Check if usage dict is already in backend-compatible format.

    Backend format has 'original_usage.' prefixed keys for provider-specific data.
    This is used to detect usage data from exports that should be passed through
    without reprocessing.
    """
    return any(key.startswith("original_usage.") for key in usage.keys())


def validate_and_parse_usage(
    usage: Any,
    logger: logging.Logger,
    provider: Optional[Union[LLMProvider, str]],
) -> Optional[Dict[str, int]]:
    if isinstance(usage, llm_usage.OpikUsage):
        return usage.to_backend_compatible_full_usage_dict()

    if usage is None:
        return usage

    # Check if usage is already in backend format (from export/import)
    # If so, return it as-is to preserve the original values
    if isinstance(usage, dict) and _is_already_backend_format(usage):
        # Filter to only keep integer values as expected by backend
        return {k: v for k, v in usage.items() if isinstance(v, int)}

    unknown_provider = (provider is None) or (not LLMProvider.has_value(provider))

    if unknown_provider:
        return _parse_usage_of_unknown_provider(usage, logger)

    provider = LLMProvider(provider)

    try:
        opik_usage = llm_usage.build_opik_usage(provider=provider, usage=usage)
        return opik_usage.to_backend_compatible_full_usage_dict()
    except Exception:
        return _parse_usage_of_unknown_provider(usage, logger)


def _parse_usage_of_unknown_provider(
    usage: Any, logger: logging.Logger
) -> Optional[Dict[str, int]]:
    opik_usage = llm_usage.build_opik_usage_from_unknown_provider(usage)
    if opik_usage is None:
        return None

    try:
        return opik_usage.to_backend_compatible_full_usage_dict()
    except Exception:
        # Flattening walks the provider payload, so pathological input (deep or
        # cyclic nesting -> RecursionError) can still fail here even though parsing
        # succeeded. This is the best-effort path and it is reached with arbitrary
        # caller data from `Opik.span(usage=...)`: the usage is droppable, the span
        # it rides on is not. Type only, never the value.
        logger.error(
            "Failed to serialize token usage of an unknown provider (received %s)",
            type(usage).__name__,
            exc_info=True,
        )
        return None


def validate_feedback_score(
    feedback_score: Any, logger: logging.Logger
) -> Optional[BatchFeedbackScoreDict]:
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

    # `bool` is a subclass of `int`, so the validator above accepts it and the
    # caller's dict is returned unchanged: a caller that passes a flag where a
    # score belongs gets one recorded as 0 or 1. The other two readers of this
    # field (`experiment.bulk_converters` and `experiment.experiment_item`)
    # both exclude bool explicitly, so a score logged through here can be
    # refused when it is read back.
    if isinstance(feedback_score, dict) and isinstance(
        feedback_score.get("value"), bool
    ):
        logger.warning(
            logging_messages.INVALID_FEEDBACK_SCORE_WILL_NOT_BE_LOGGED,
            feedback_score,
            "a feedback score value must be a number, not a bool",
        )
        return None

    return cast(BatchFeedbackScoreDict, feedback_score)


def validate_bounded_positive_int(
    value: Any, name: str, maximum: Optional[int] = None
) -> None:
    """Raise ``ValueError`` unless ``value`` is a positive int within ``maximum``."""
    if isinstance(value, bool) or not isinstance(value, int) or value < 1:
        raise ValueError(f"{name} must be a positive integer")
    if maximum is not None and value > maximum:
        raise ValueError(f"{name} must not exceed {maximum}, got {value}")
