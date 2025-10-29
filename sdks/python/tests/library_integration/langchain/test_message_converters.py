"""
Legacy LangChain compatibility smoke tests.

Different LangChain releases expose ``messages_to_dict`` from different modules.
Attempt each location and fail loudly if none are available so we get signal when
new releases move the helper again.
"""

import importlib
from typing import Callable, List

from langchain_core.messages import HumanMessage, SystemMessage
from langchain_core.prompts import ChatPromptTemplate

from opik.evaluation.models.langchain.message_converters import (
    convert_to_langchain_messages,
)


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
