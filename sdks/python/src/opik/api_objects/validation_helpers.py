import logging
from typing import Any, Optional, cast, Union, Dict

from ..types import FeedbackScoreDict
from ..validation import feedback_score as feedback_score_validator
from .. import logging_messages, llm_usage
from opik.types import LLMProvider


def validate_and_parse_usage(
    usage: Any,
    logger: logging.Logger,
    provider: Optional[Union[LLMProvider, str]],
) -> Optional[Dict[str, int]]:
    if isinstance(usage, llm_usage.OpikUsage):
        return usage.to_backend_compatible_full_usage_dict()

    if usage is None:
        return usage

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
