import pytest
import mock
import openai
import os
import asyncio

import opik
from opik.message_processing import streamer_constructors
from opik.integrations.openai import track_openai
from ...testlib import backend_emulator_message_processor
from ...testlib import (
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    assert_equal,
)


# TODO: make sure that the output logged to Comet is exactly as from the response?
# Existing tests only check that output is logged and its structure is {choices: ANY_BUT_NONE}


@pytest.fixture(autouse=True)
def ensure_openai_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if not ("OPENAI_API_KEY" in os.environ and "OPENAI_ORG_ID" in os.environ):
        raise Exception("OpenAI not configured!")


def test_openai_client_chat_completions_create__happyflow(fake_streamer):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        client = openai.OpenAI()
        wrapped_client = track_openai(client)
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Tell a fact"},
        ]

        _ = wrapped_client.chat.completions.create(
            model="gpt-3.5-turbo",
            messages=messages,
            max_tokens=10,
        )

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="chat_completion_create",
            input={"messages": messages},
            output={"choices": ANY_BUT_NONE},
            tags=["openai"],
            metadata={
                "created_from": "openai",
                "type": "openai_chat",
                "model": "gpt-3.5-turbo",
                "max_tokens": 10,
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="chat_completion_create",
                    input={"messages": messages},
                    output={"choices": ANY_BUT_NONE},
                    tags=["openai"],
                    metadata={
                        "created_from": "openai",
                        "type": "openai_chat",
                        "model": "gpt-3.5-turbo",
                        "max_tokens": 10,
                        "usage": ANY_BUT_NONE,
                    },
                    usage=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_openai_client_chat_completions_create__create_raises_an_error__span_and_trace_finished_gracefully(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        client = openai.OpenAI()
        wrapped_client = track_openai(client)

        with pytest.raises(openai.OpenAIError):
            _ = wrapped_client.chat.completions.create(
                messages=None,
                model=None,
            )

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="chat_completion_create",
            input={"messages": None},
            output=None,
            tags=["openai"],
            metadata={
                "created_from": "openai",
                "type": "openai_chat",
                "model": None,
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="chat_completion_create",
                    input={"messages": None},
                    output=None,
                    tags=["openai"],
                    metadata={
                        "created_from": "openai",
                        "type": "openai_chat",
                        "model": None,
                    },
                    usage=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_openai_client_chat_completions_create__openai_call_made_in_another_tracked_function__openai_span_attached_to_existing_trace(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Tell a fact"},
        ]

        @opik.track()
        def f():
            client = openai.OpenAI()
            wrapped_client = track_openai(client)
            _ = wrapped_client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=messages,
                max_tokens=10,
            )

        f()

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="f",
            input={},
            output=None,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="llm",
                            name="chat_completion_create",
                            input={"messages": messages},
                            output={"choices": ANY_BUT_NONE},
                            tags=["openai"],
                            metadata={
                                "created_from": "openai",
                                "type": "openai_chat",
                                "model": "gpt-3.5-turbo",
                                "max_tokens": 10,
                                "usage": ANY_BUT_NONE,
                            },
                            usage=ANY_BUT_NONE,
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        )
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_openai_client_chat_completions_create__async_openai_call_made_in_another_tracked_async_function__openai_span_attached_to_existing_trace(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Tell a fact"},
        ]

        @opik.track()
        async def async_f():
            client = openai.AsyncOpenAI()
            wrapped_client = track_openai(client)
            _ = await wrapped_client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=messages,
                max_tokens=10,
            )

        asyncio.run(async_f())

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="async_f",
            input={},
            output=None,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="async_f",
                    input={},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="llm",
                            name="chat_completion_create",
                            input={"messages": messages},
                            output={"choices": ANY_BUT_NONE},
                            tags=["openai"],
                            metadata={
                                "created_from": "openai",
                                "type": "openai_chat",
                                "model": "gpt-3.5-turbo",
                                "max_tokens": 10,
                                "usage": ANY_BUT_NONE,
                            },
                            usage=ANY_BUT_NONE,
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        )
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_openai_client_chat_completions_create__stream_mode_is_on__generator_tracked_correctly(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        client = openai.OpenAI()
        wrapped_client = track_openai(client)
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Tell a fact"},
        ]

        stream = wrapped_client.chat.completions.create(
            model="gpt-3.5-turbo",
            messages=messages,
            max_tokens=10,
            stream=True,
            stream_options={"include_usage": True},
        )

        for item in stream:
            pass

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="chat_completion_create",
            input={"messages": messages},
            output={"choices": ANY_BUT_NONE},
            tags=["openai"],
            metadata={
                "created_from": "openai",
                "type": "openai_chat",
                "model": "gpt-3.5-turbo",
                "max_tokens": 10,
                "stream": True,
                "stream_options": {"include_usage": True},
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="chat_completion_create",
                    input={"messages": messages},
                    output={"choices": ANY_BUT_NONE},
                    tags=["openai"],
                    metadata={
                        "created_from": "openai",
                        "type": "openai_chat",
                        "model": "gpt-3.5-turbo",
                        "max_tokens": 10,
                        "stream": True,
                        "stream_options": {"include_usage": True},
                        "usage": ANY_BUT_NONE,
                    },
                    usage=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_openai_client_chat_completions_create__async_openai_call_made_in_another_tracked_async_function__streaming_mode_enabled__openai_span_attached_to_existing_trace(
    fake_streamer,
):
    fake_message_processor_: (
        backend_emulator_message_processor.BackendEmulatorMessageProcessor
    )
    streamer, fake_message_processor_ = fake_streamer

    mock_construct_online_streamer = mock.Mock()
    mock_construct_online_streamer.return_value = streamer

    with mock.patch.object(
        streamer_constructors,
        "construct_online_streamer",
        mock_construct_online_streamer,
    ):
        messages = [
            {"role": "system", "content": "You are a helpful assistant."},
            {"role": "user", "content": "Tell a fact"},
        ]

        @opik.track()
        async def async_f():
            client = openai.AsyncOpenAI()
            wrapped_client = track_openai(client)
            stream = await wrapped_client.chat.completions.create(
                model="gpt-3.5-turbo",
                messages=messages,
                max_tokens=10,
                stream=True,
                stream_options={"include_usage": True},
            )
            async for item in stream:
                pass

        asyncio.run(async_f())

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="async_f",
            input={},
            output=None,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="async_f",
                    input={},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            type="llm",
                            name="chat_completion_create",
                            input={"messages": messages},
                            output={"choices": ANY_BUT_NONE},
                            tags=["openai"],
                            metadata={
                                "created_from": "openai",
                                "type": "openai_chat",
                                "model": "gpt-3.5-turbo",
                                "max_tokens": 10,
                                "stream": True,
                                "stream_options": {"include_usage": True},
                                "usage": ANY_BUT_NONE,
                            },
                            usage=ANY_BUT_NONE,
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            spans=[],
                        )
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])
