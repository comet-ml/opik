"""
Legacy LangChain compatibility smoke tests.

Different LangChain releases expose ``messages_to_dict`` from different modules.
Attempt each location and fail loudly if none are available so we get signal when
new releases move the helper again.
"""

import importlib
from typing import Callable, List

import pytest

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.prompts import ChatPromptTemplate

from opik.evaluation.models.langchain.message_converters import (
    convert_to_langchain_messages,
)


def test_convert_to_langchain_messages_with_plain_text() -> None:
    messages = [{"role": "user", "content": "Hello world"}]

    converted = convert_to_langchain_messages(messages)

    assert len(converted) == 1
    assert converted[0].type == "human"
    assert converted[0].content == "Hello world"


def test_convert_to_langchain_messages_with_structured_content() -> None:
    structured_content = [
        {"type": "text", "text": "Describe the image"},
        {"type": "image_url", "image_url": {"url": "https://example.com/cat.png"}},
    ]

    messages = [{"role": "user", "content": structured_content}]
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
        convert_to_langchain_messages([{"role": "critic", "content": "text"}])


def _resolve_messages_to_dict() -> Callable[[List[HumanMessage]], List[dict]]:
    candidates = [
        ("langchain.schema", "messages_to_dict"),
        ("langchain_core.messages.utils", "messages_to_dict"),
        ("langchain_core.messages", "messages_to_dict"),
    ]

    for module_name, attr in candidates:
        try:
            module = importlib.import_module(module_name)
            fn = getattr(module, attr, None)
            if fn is not None:
                return fn  # type: ignore[return-value]
        except ModuleNotFoundError:
            continue

    raise ImportError(
        "LangChain messages_to_dict helper not available; please upgrade langchain-core"
    )


messages_to_dict = _resolve_messages_to_dict()


def test_convert_to_langchain_messages_accepts_langchain_message_objects() -> None:
    langchain_messages = [
        SystemMessage(content="You are an assistant."),
        HumanMessage(content="Describe the weather in Paris."),
    ]

    converted = convert_to_langchain_messages(messages_to_dict(langchain_messages))

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
    converted = convert_to_langchain_messages(messages_to_dict(rendered.messages))

    assert len(converted) == 2
    assert converted[1].type == "human"
    human_content = converted[1].content
    assert isinstance(human_content, list)
    assert human_content[1]["image_url"]["detail"] == "high"
