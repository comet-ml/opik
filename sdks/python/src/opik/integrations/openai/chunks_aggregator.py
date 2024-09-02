import logging
import dataclasses
from typing import Any, List, Optional, Dict
from openai.types.chat import chat_completion_chunk

from opik import logging_messages

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class ExtractedStreamContent:
    choices: List[Dict[str, Any]] = dataclasses.field(default_factory=list)
    usage: Optional[Dict[str, Any]] = None


def aggregate(
    items: List[chat_completion_chunk.ChatCompletionChunk],
) -> ExtractedStreamContent:
    extracted_content = ExtractedStreamContent()

    # TODO: check if there are scenarios when stream contains more than one choice
    try:
        content_items = [_get_item_content(item) for item in items]
        choices = [_construct_choice_dict(items[0], content_items)]
        usage = items[-1].usage
        if usage is not None:
            usage = usage.model_dump()

        extracted_content = ExtractedStreamContent(
            choices=choices,
            usage=usage,
        )
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )

    return extracted_content


def _get_item_content(item: chat_completion_chunk.ChatCompletionChunk) -> str:
    result: Optional[str] = None
    if len(item.choices) > 0:
        result = item.choices[0].delta.content

    return "" if result is None else result


def _construct_choice_dict(
    first_item: chat_completion_chunk.ChatCompletionChunk, items_content: List[str]
) -> Dict[str, Any]:
    if len(first_item.choices) > 0:
        role = first_item.choices[0].delta.role
    else:
        role = None

    choice_info = {
        "message": {"content": "".join(items_content), "role": role},
    }

    return choice_info
