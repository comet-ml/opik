import functools
import importlib.metadata
from typing import Any, Dict

import haystack.dataclasses

import opik.semantic_version as semantic_version


def convert_message_to_openai_format(
    message: haystack.dataclasses.ChatMessage,
) -> Dict[str, Any]:
    """
    Convert a Haystack ChatMessage to OpenAI format.

    This function handles version differences in Haystack, using the appropriate
    conversion method based on the installed version.

    Args:
        message: The Haystack ChatMessage to convert.

    Returns:
        Dictionary representation of the message in OpenAI format.
    """
    if _haystack_version_less_than_2_9_0():
        # 2.8.1 and less use _convert_message_to_openai_format function
        from haystack.components.generators.openai import (
            _convert_message_to_openai_format,
        )

        return _convert_message_to_openai_format(message=message)
    else:
        # 2.9.0 introduced to_openai_dict_format
        return message.to_openai_dict_format()


@functools.lru_cache
def _haystack_version_less_than_2_9_0() -> bool:
    haystack_version = importlib.metadata.version("haystack-ai")
    result = semantic_version.SemanticVersion.parse(haystack_version) < "2.9.0"  # type: ignore

    return result
