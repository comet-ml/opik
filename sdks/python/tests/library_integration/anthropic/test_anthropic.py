import pytest
import mock
import os
import asyncio

import opik
from opik.message_processing import streamer_constructors
from opik.integrations.anthropic import track_anthropic
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from ...testlib import backend_emulator_message_processor
from ...testlib import (
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    assert_equal,
)

import anthropic


@pytest.fixture(autouse=True)
def ensure_anthropic_configured():
    # don't use assertion here to prevent printing os.environ with all env variables

    if "ANTHROPIC_API_KEY" not in os.environ:
        raise Exception("Anthropic not configured!")


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("anthropic-integration-test", "anthropic-integration-test"),
    ],
)
def test_anthropic_messages_create__happyflow(
    fake_streamer, project_name, expected_project_name
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
        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(
            anthropic_client=client,
            project_name=project_name,
        )
        messages = [{"role": "user", "content": "Tell a short fact"}]

        response = wrapped_client.messages.create(
            model="claude-3-opus-20240229",
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_create",
            input={"messages": messages, "system": "You are a helpful assistant"},
            output={"content": response.model_dump()["content"]},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=expected_project_name,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_create",
                    input={
                        "messages": messages,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": response.model_dump()["content"]},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=expected_project_name,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_anthropic_messages_create__create_raises_an_error__span_and_trace_finished_gracefully(
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
        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(client)

        with pytest.raises(Exception):
            _ = wrapped_client.messages.create(
                messages=None,
                model=None,
            )

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_create",
            input={"messages": None},
            output=None,
            tags=["anthropic"],
            metadata={
                "created_from": "anthropic",
                "model": None,
            },
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            project_name=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    type="llm",
                    name="anthropic_messages_create",
                    input={"messages": None},
                    output=None,
                    tags=["anthropic"],
                    metadata={
                        "created_from": "anthropic",
                        "model": None,
                    },
                    usage=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=ANY_BUT_NONE,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_anthropic_messages_create__create_call_made_in_another_tracked_function__anthropic_span_attached_to_existing_trace(
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
            {"role": "user", "content": "Tell a short fact"},
        ]

        @opik.track(project_name="anthropic-integration-test")
        def f():
            client = anthropic.Anthropic()
            wrapped_client = track_anthropic(
                anthropic_client=client,
                project_name="anthropic-integration-test",
            )
            messages = [
                {
                    "role": "user",
                    "content": "Tell a short fact",
                }
            ]

            _ = wrapped_client.messages.create(
                model="claude-3-opus-20240229",
                messages=messages,
                max_tokens=10,
                system="You are a helpful assistant",
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
            project_name="anthropic-integration-test",
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="f",
                    input={},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name="anthropic-integration-test",
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            name="anthropic_messages_create",
                            input={
                                "messages": messages,
                                "system": "You are a helpful assistant",
                            },
                            output={"content": ANY_LIST},
                            tags=["anthropic"],
                            metadata=ANY_DICT,
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            project_name="anthropic-integration-test",
                            type="llm",
                            usage=ANY_DICT,
                            spans=[],
                        )
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_async_anthropic_messages_create_call_made_in_another_tracked_async_function__anthropic_span_attached_to_existing_trace(
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
            {"role": "user", "content": "Tell a short fact"},
        ]

        @opik.track()
        async def async_f():
            client = anthropic.AsyncAnthropic()
            wrapped_client = track_anthropic(client)
            _ = await wrapped_client.messages.create(
                model="claude-3-opus-20240229",
                messages=messages,
                max_tokens=10,
                system="You are a helpful assistant",
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
            project_name=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="async_f",
                    input={},
                    output=None,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    project_name=ANY_BUT_NONE,
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            name="anthropic_messages_create",
                            input={
                                "messages": messages,
                                "system": "You are a helpful assistant",
                            },
                            output={"content": ANY_LIST},
                            tags=["anthropic"],
                            metadata=ANY_DICT,
                            start_time=ANY_BUT_NONE,
                            end_time=ANY_BUT_NONE,
                            project_name=ANY_BUT_NONE,
                            type="llm",
                            usage=ANY_DICT,
                            spans=[],
                        )
                    ],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_anthropic_messages_stream__generator_tracked_correctly(
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
        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(client)
        messages = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]

        message_stream_manager = wrapped_client.messages.stream(
            model="claude-3-opus-20240229",
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        with message_stream_manager as stream:
            for _ in stream:
                pass

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": messages, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": messages,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_anthropic_messages_stream__stream_called_2_times__generator_tracked_correctly(
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

        def run_stream(client, messages):
            message_stream_manager = wrapped_client.messages.stream(
                model="claude-3-opus-20240229",
                messages=messages,
                max_tokens=10,
                system="You are a helpful assistant",
            )
            with message_stream_manager as stream:
                for _ in stream:
                    pass

        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(client)

        SHORT_FACT_MESSAGES = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]
        JOKE_MESSAGES = [
            {
                "role": "user",
                "content": "Tell a short joke",
            }
        ]
        run_stream(wrapped_client, messages=SHORT_FACT_MESSAGES)
        run_stream(wrapped_client, messages=JOKE_MESSAGES)

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE_WITH_SHORT_FACT = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={
                "messages": SHORT_FACT_MESSAGES,
                "system": "You are a helpful assistant",
            },
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": SHORT_FACT_MESSAGES,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )
        EXPECTED_TRACE_TREE_WITH_JOKE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": JOKE_MESSAGES, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": JOKE_MESSAGES,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 2

        assert_equal(
            EXPECTED_TRACE_TREE_WITH_SHORT_FACT, fake_message_processor_.trace_trees[0]
        )
        assert_equal(
            EXPECTED_TRACE_TREE_WITH_JOKE, fake_message_processor_.trace_trees[1]
        )


def test_anthropic_messages_stream__stream_called_2_times__second_stream_is_being_read_first__both_are_tracked__the_one_that_was_read_first_is_logged_first(
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
        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(client)

        SHORT_FACT_MESSAGES = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]
        JOKE_MESSAGES = [
            {
                "role": "user",
                "content": "Tell a short joke",
            }
        ]

        joke_message_stream_manager = wrapped_client.messages.stream(
            model="claude-3-opus-20240229",
            messages=JOKE_MESSAGES,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        fact_message_stream_manager = wrapped_client.messages.stream(
            model="claude-3-opus-20240229",
            messages=SHORT_FACT_MESSAGES,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        with fact_message_stream_manager as fact_stream:
            for _ in fact_stream:
                pass
        
        with joke_message_stream_manager as joke_stream:
            for _ in joke_stream:
                pass


        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE_WITH_SHORT_FACT = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={
                "messages": SHORT_FACT_MESSAGES,
                "system": "You are a helpful assistant",
            },
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": SHORT_FACT_MESSAGES,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )
        EXPECTED_TRACE_TREE_WITH_JOKE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": JOKE_MESSAGES, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": JOKE_MESSAGES,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 2

        assert_equal(
            EXPECTED_TRACE_TREE_WITH_SHORT_FACT, fake_message_processor_.trace_trees[1]
        )
        assert_equal(
            EXPECTED_TRACE_TREE_WITH_JOKE, fake_message_processor_.trace_trees[0]
        )


def test_anthropic_messages_stream__get_final_message_called__generator_tracked_correctly(
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
        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(client)
        messages = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]

        message_stream_manager = wrapped_client.messages.stream(
            model="claude-3-opus-20240229",
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        with message_stream_manager as stream:
            stream.get_final_message()

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": messages, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": messages,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_anthropic_messages_stream__get_final_message_called_after_stream_iteration_loop__generator_tracked_correctly_only_once(
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
        client = anthropic.Anthropic()
        wrapped_client = track_anthropic(client)
        messages = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]

        message_stream_manager = wrapped_client.messages.stream(
            model="claude-3-opus-20240229",
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        with message_stream_manager as stream:
            for _ in stream:
                pass
            stream.get_final_message()

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": messages, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": messages,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_async_anthropic_messages_stream__data_tracked_correctly(
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
        client = anthropic.AsyncAnthropic()
        wrapped_client = track_anthropic(client)
        messages = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]
        async def async_f():
            message_stream_manager = wrapped_client.messages.stream(
                model="claude-3-opus-20240229",
                messages=messages,
                max_tokens=10,
                system="You are a helpful assistant",
            )
            async with message_stream_manager as stream:
                async for _ in stream:
                    pass
        
        asyncio.run(async_f())

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": messages, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": messages,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])


def test_async_anthropic_messages_stream__get_final_message_called_twice__data_tracked_correctly_once(
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
        client = anthropic.AsyncAnthropic()
        wrapped_client = track_anthropic(client)
        messages = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]
        async def async_f():
            message_stream_manager = wrapped_client.messages.stream(
                model="claude-3-opus-20240229",
                messages=messages,
                max_tokens=10,
                system="You are a helpful assistant",
            )
            async with message_stream_manager as stream:
                await stream.get_final_message()
                await stream.get_final_message()

        
        asyncio.run(async_f())

        opik.flush_tracker()
        mock_construct_online_streamer.assert_called_once()

        EXPECTED_TRACE_TREE = TraceModel(
            id=ANY_BUT_NONE,
            name="anthropic_messages_stream",
            input={"messages": messages, "system": "You are a helpful assistant"},
            output={"content": ANY_LIST},
            tags=["anthropic"],
            metadata=ANY_DICT,
            start_time=ANY_BUT_NONE,
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    name="anthropic_messages_stream",
                    input={
                        "messages": messages,
                        "system": "You are a helpful assistant",
                    },
                    output={"content": ANY_LIST},
                    tags=["anthropic"],
                    metadata=ANY_DICT,
                    start_time=ANY_BUT_NONE,
                    end_time=ANY_BUT_NONE,
                    type="llm",
                    usage=ANY_DICT,
                    spans=[],
                )
            ],
        )

        assert len(fake_message_processor_.trace_trees) == 1

        assert_equal(EXPECTED_TRACE_TREE, fake_message_processor_.trace_trees[0])
