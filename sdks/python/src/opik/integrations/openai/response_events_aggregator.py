import logging
from typing import List, Optional

from openai.types import responses as openai_responses

import opik.logging_messages as logging_messages

LOGGER = logging.getLogger(__name__)


def aggregate(
    items: List[openai_responses.ResponseStreamEvent],
) -> Optional[openai_responses.Response]:
    try:
        completed_event = [
            event
            for event in items
            if isinstance(
                event,
                (
                    openai_responses.ResponseCompletedEvent,
                    openai_responses.ResponseErrorEvent,
                    openai_responses.ResponseIncompleteEvent,
                ),
            )
        ]

        response = completed_event[0].response
        return response
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )
        return None
