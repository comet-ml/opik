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
        return llm_usage.build_opik_usage_from_unknown_provider(
            usage
        ).to_backend_compatible_full_usage_dict()

    provider = LLMProvider(provider)

    try:
        opik_usage = llm_usage.build_opik_usage(provider=provider, usage=usage)
        return opik_usage.to_backend_compatible_full_usage_dict()
    except Exception:
        return llm_usage.build_opik_usage_from_unknown_provider(
            usage
        ).to_backend_compatible_full_usage_dict()


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

    return cast(BatchFeedbackScoreDict, feedback_score)
