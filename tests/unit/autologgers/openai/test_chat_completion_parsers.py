import pytest

from comet_llm.autologgers.openai import chat_completion_parsers


def test_generate__all_data_presented():
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


def test_generate__function_call_not_presented():
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


def test_generate__only_messages_presented():
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
