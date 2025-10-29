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


def test_convert_to_langchain_messages_supports_tool_role() -> None:
    message = {
        "role": "tool",
        "content": "tool output",
        "tool_call_id": "call-1",
    }

    converted = convert_to_langchain_messages([message])

    assert len(converted) == 1
    assert converted[0].type == "tool"
    assert converted[0].content == "tool output"


def test_convert_to_langchain_messages_supports_function_role() -> None:
    message = {
        "role": "function",
        "name": "lookup",
        "content": "{}",
    }

    converted = convert_to_langchain_messages([message])

    assert len(converted) == 1
    assert converted[0].type == "function"
    assert converted[0].content == "{}"


def test_convert_to_langchain_messages_validates_required_metadata() -> None:
    tool_message = {"role": "tool", "content": "ignored"}
    function_message = {"role": "function", "content": "{}"}

    with pytest.raises(ValueError):
        convert_to_langchain_messages([tool_message])

    with pytest.raises(ValueError):
        convert_to_langchain_messages([function_message])


def test_convert_to_langchain_messages_rejects_unknown_roles() -> None:
    with pytest.raises(ValueError):
        convert_to_langchain_messages([_build_message("critic", "text")])
