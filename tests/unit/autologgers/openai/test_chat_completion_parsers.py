import pytest
import box
from testix import *

from comet_llm.autologgers.openai import chat_completion_parsers



def test_parse_create_arguments__all_data_presented():
    KWARGS = {
        "messages": "the-messages",
        "function_call": "the-function-call",
        "some-key-1": "some-value-1",
        "some-key-2": "some-value-2"
    }

    assert chat_completion_parsers.parse_create_arguments(KWARGS) == (
        {
            "messages": "the-messages",
            "function_call": "the-function-call"
        },
        {
            "some-key-1": "some-value-1",
            "some-key-2": "some-value-2",
            "created_from": "openai",
            "type": "openai_chat"
        }
    )


def test_parse_create_arguments__function_call_not_presented():
    KWARGS = {
        "messages": "the-messages",
        "some-key-1": "some-value-1",
        "some-key-2": "some-value-2"
    }

    assert chat_completion_parsers.parse_create_arguments(KWARGS) == (
        {
            "messages": "the-messages",
        },
        {
            "some-key-1": "some-value-1",
            "some-key-2": "some-value-2",
            "created_from": "openai",
            "type": "openai_chat"
        }
    )


def test_parse_create_arguments__only_messages_presented():
    KWARGS = {
        "messages": "the-messages",
    }

    assert chat_completion_parsers.parse_create_arguments(KWARGS) == (
        {"messages": "the-messages"},
        {
            "created_from": "openai",
            "type": "openai_chat"
        }
    )


def test_parse_create_result__happyflow():
    create_result = box.Box(choices=[Fake("choice_1"), Fake("choice_2")], usage=Fake("usage"))

    with Scenario() as s:
        s.choice_1.message.to_dict() >> "choice-1"
        s.choice_2.message.to_dict() >> "choice-2"
        s.usage.to_dict() >> "the-usage"

        outputs, metadata = chat_completion_parsers.parse_create_result(create_result)

        assert outputs == {"choices": ["choice-1", "choice-2"]}
        assert metadata == {"usage": "the-usage"}