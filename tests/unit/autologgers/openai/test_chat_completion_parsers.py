import box
import pytest
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


def test_parse_create_result__input_is_openai_object__input_parsed_successfully():
    create_result = Fake("create_result")
    with Scenario() as s:
        s.create_result.to_dict() >> {
            "choices": "the-choices",
            "some-key": "some-value"
        }

        outputs, metadata = chat_completion_parsers.parse_create_result(create_result)

        assert outputs == {"choices": "the-choices"}
        assert metadata == {"some-key": "some-value"}


def test_parse_create_result__input_is_generator_object__input_parsed_with_hardcoded_values_used():
    create_result = (x for x in [])

    outputs, metadata = chat_completion_parsers.parse_create_result(create_result)

    assert outputs == {"choices": "Generation is not logged when using stream mode"}
    assert metadata == {}


@pytest.mark.parametrize(
    "inputs,result",
    [
        ({"messages": "some-messages"}, True),
        ({}, False),
        ({"some-key": "some-value"}, False)
    ]
)
def test_create_arguments_supported__happyflow(inputs, result):
    assert chat_completion_parsers.create_arguments_supported(inputs) is result