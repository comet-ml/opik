import logging
import dataclasses
from typing import Any, List, Optional, Dict
from openai.types.chat import chat_completion_chunk

from opik import logging_messages
from opik import dict_utils

LOGGER = logging.getLogger(__name__)


@dataclasses.dataclass
class AggregatedStreamOutput:
    output: Dict[str, Any]
    metadata: Dict[str, Any]
    usage: Optional[Dict[str, Any]] = None

def aggregate(
    items: List[chat_completion_chunk.ChatCompletionChunk],
) -> AggregatedStreamOutput:
    # TODO: check if there are scenarios when stream contains more than one choice
    try:
        content_items = [_get_item_content(item) for item in items]
        choices = [_construct_choice_dict(items[0], content_items)]

        output = {"choices": choices}

        last_item = items[-1]

        usage = last_item.usage.model_dump() if last_item.usage is not None else None
        _, metadata = dict_utils.split_dict_by_keys(last_item.model_dump(), ["choices"])

        extracted_content = AggregatedStreamOutput(
            output=output,
            usage=usage,
            metadata=metadata,
        )

        return extracted_content
    except Exception as exception:
        LOGGER.error(
            logging_messages.FAILED_TO_PARSE_OPENAI_STREAM_CONTENT,
            str(exception),
            exc_info=True,
        )

        return AggregatedStreamOutput({}, {}, None)


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
