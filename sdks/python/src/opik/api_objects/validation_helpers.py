import logging
from typing import Any, Optional, cast

from ..types import UsageDict, FeedbackScoreDict
from ..validation import usage as usage_validator
from ..validation import feedback_score as feedback_score_validator
from .. import logging_messages


def validate_usage_and_print_result(
    usage: Any, logger: logging.Logger
) -> Optional[UsageDict]:
    if usage is None:
        return None

    usage_validator_ = usage_validator.UsageValidator(usage)
    if usage_validator_.validate().failed():
        logger.warning(
            logging_messages.INVALID_USAGE_WILL_NOT_BE_LOGGED,
            usage,
            usage_validator_.failure_reason_message(),
        )
        return None

    return cast(UsageDict, usage)


def validate_feedback_score_and_print_result(
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
