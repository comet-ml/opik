from typing import Any, Dict, List

import pytest
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.prompts import ChatPromptTemplate

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


def test_convert_to_langchain_messages_accepts_langchain_message_objects() -> None:
    langchain_messages = [
        SystemMessage(content="You are an assistant."),
        HumanMessage(content="Describe the weather in Paris."),
    ]

    converted = convert_to_langchain_messages(
        [message.model_dump() for message in langchain_messages]
    )

    assert len(converted) == 2
    assert converted[0].type == "system"
    assert converted[1].type == "human"
    assert converted[1].content == "Describe the weather in Paris."


def test_convert_to_langchain_messages_handles_chat_prompt_template() -> None:
    prompt = ChatPromptTemplate.from_messages(
        [
            ("system", "You are a helpful assistant."),
            (
                "user",
                [
                    {"type": "text", "text": "Describe the following image."},
                    {
                        "type": "image_url",
                        "image_url": {
                            "url": "https://python.langchain.com/img/phone_handoff.jpeg",
                            "detail": "high",
                        },
                    },
                ],
            ),
        ]
    )

    rendered = prompt.invoke({})
    converted = convert_to_langchain_messages(
        [message.model_dump() for message in rendered.messages]
    )

    assert len(converted) == 2
    assert converted[1].type == "human"
    human_content = converted[1].content
    assert isinstance(human_content, list)
    assert human_content[1]["image_url"]["detail"] == "high"
