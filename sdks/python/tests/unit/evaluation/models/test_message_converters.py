from typing import Any, Dict, List

import pytest

from opik.evaluation.models.langchain.message_converters import (
    ContentType,
    convert_to_langchain_messages,
)


def _build_message(role: str, content: ContentType) -> Dict[str, Any]:
    return {"role": role, "content": content}


def test_convert_to_langchain_messages_with_plain_text() -> None:
    messages = [_build_message("user", "Hello world")]

    converted = convert_to_langchain_messages(messages)

    assert len(converted) == 1
    assert converted[0].type == "human"
    assert converted[0].content == "Hello world"


def test_convert_to_langchain_messages_with_structured_content() -> None:
    structured_content: List[Dict[str, Any]] = [
        {"type": "text", "text": "Describe the image"},
        {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}},
    ]

    messages = [_build_message("user", structured_content)]
    converted = convert_to_langchain_messages(messages)

    assert len(converted) == 1
    assert converted[0].type == "human"
    assert converted[0].content == structured_content


def test_convert_to_langchain_messages_validates_roles() -> None:
    messages = [_build_message("tool", "ignored")]

    with pytest.raises(ValueError):
        convert_to_langchain_messages(messages)
