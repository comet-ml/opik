import logging
from typing import List, Optional, Any

from opik import logging_messages

LOGGER = logging.getLogger(__name__)


def aggregate(
    items: List[Any],
) -> Optional[Any]:
    try:
        if len(items) == 0:
            return None

        last_item = items[-1]
        if hasattr(last_item, "response"):
            return last_item.response  # type: ignore[attr-defined]
        return None
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )
        return None
