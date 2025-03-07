import logging
from typing import Any, Optional, cast, Union

from ..types import FeedbackScoreDict
from ..validation import feedback_score as feedback_score_validator
from .. import logging_messages, llm_usage
from opik.types import LLMProvider


def validate_and_parse_usage(
    usage: Any,
    logger: logging.Logger,
    provider: Optional[Union[LLMProvider, str]],
) -> Optional[llm_usage.OpikUsage]:
    if isinstance(usage, llm_usage.OpikUsage) or usage is None:
        return usage

    default_provider_used = False

    if provider is not None and LLMProvider.has_value(provider):
        provider = LLMProvider(provider)
    else:
        default_provider_used = True
        provider = LLMProvider.OPENAI
    try:
        opik_usage = llm_usage.build_opik_usage(provider=provider, usage=usage)
        return opik_usage
    except Exception:
        logger.warning(
            "The usage %s will not be logged because it does not follow expected format for the given provider: %s."
            "Make sure you specified the correct provider or the usage dict is valid for the given provider",
            usage,
            f"{provider} (default if not set or not recognized)"
            if default_provider_used
            else provider,
            exc_info=True,
        )

        return None


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
