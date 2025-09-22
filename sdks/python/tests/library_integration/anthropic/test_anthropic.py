import pytest
import asyncio

import opik
from opik.integrations.anthropic import track_anthropic
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from ...testlib import (
    SpanModel,
    TraceModel,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    ANY,
    assert_equal,
)

import anthropic
import tenacity


def _is_internal_server_error(exception: Exception) -> bool:
    if isinstance(exception, anthropic.APIStatusError):
        return exception.status_code >= 500 and exception.status_code < 600

    return False


retry_on_internal_server_errors = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_incrementing(start=5, increment=5),
    retry=tenacity.retry_if_exception(_is_internal_server_error),
)

EXPECTED_ANTHROPIC_USAGE_DICT = {
    "completion_tokens": ANY_BUT_NONE,
    "prompt_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.input_tokens": ANY_BUT_NONE,
    "original_usage.output_tokens": ANY_BUT_NONE,
    "original_usage.cache_creation_input_tokens": ANY_BUT_NONE,
    "original_usage.cache_read_input_tokens": ANY_BUT_NONE,
    "original_usage.cache_creation.ephemeral_5m_input_tokens": ANY_BUT_NONE,
    "original_usage.cache_creation.ephemeral_1h_input_tokens": ANY_BUT_NONE,
}

MODEL_FOR_TESTS_FULL = "claude-sonnet-4-0"
MODEL_FOR_TESTS_SHORT = "claude-sonnet-4"

pytestmark = pytest.mark.usefixtures("ensure_anthropic_configured")


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("anthropic-integration-test", "anthropic-integration-test"),
    ],
)
@retry_on_internal_server_errors
def test_anthropic_messages_create__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = anthropic.Anthropic()
    wrapped_client = track_anthropic(
        anthropic_client=client,
        project_name=project_name,
    )
    messages = [{"role": "user", "content": "Tell a short fact"}]

    response = wrapped_client.messages.create(
        model=MODEL_FOR_TESTS_FULL,
        messages=messages,
        max_tokens=10,
        system="You are a helpful assistant",
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_create",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": response.model_dump()["content"]},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_anthropic_messages_create__create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    client = anthropic.Anthropic()
    wrapped_client = track_anthropic(client)

    with pytest.raises(Exception):
        _ = wrapped_client.messages.create(
            messages=None,
            model=None,
        )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_create",
        input={"messages": None},
        output=None,
        tags=["anthropic"],
        metadata={"created_from": "anthropic", "model": None, "base_url": ANY},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        error_info={
            "exception_type": ANY_STRING,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
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
                    "base_url": ANY,
                },
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_anthropic_messages_create__create_call_made_in_another_tracked_function__anthropic_span_attached_to_existing_trace(
    fake_backend,
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
            model=MODEL_FOR_TESTS_FULL,
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )

    f()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                        usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                        model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                        provider="anthropic",
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_async_anthropic_messages_create_call_made_in_another_tracked_async_function__anthropic_span_attached_to_existing_trace(
    fake_backend,
):
    messages = [
        {"role": "user", "content": "Tell a short fact"},
    ]

    @opik.track()
    async def async_f():
        client = anthropic.AsyncAnthropic()
        wrapped_client = track_anthropic(client)
        _ = await wrapped_client.messages.create(
            model=MODEL_FOR_TESTS_FULL,
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f",
        input={},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                        usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                        model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                        provider="anthropic",
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_anthropic_messages_stream__generator_tracked_correctly(
    fake_backend,
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
        model=MODEL_FOR_TESTS_FULL,
        messages=messages,
        max_tokens=10,
        system="You are a helpful assistant",
    )
    with message_stream_manager as stream:
        for _ in stream:
            pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_stream",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_anthropic_messages_stream__stream_called_2_times__generator_tracked_correctly(
    fake_backend,
):
    def run_stream(client, messages):
        message_stream_manager = wrapped_client.messages.stream(
            model=MODEL_FOR_TESTS_FULL,
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
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
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
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 2

    assert_equal(EXPECTED_TRACE_TREE_WITH_SHORT_FACT, fake_backend.trace_trees[0])
    assert_equal(EXPECTED_TRACE_TREE_WITH_JOKE, fake_backend.trace_trees[1])


@retry_on_internal_server_errors
def test_anthropic_messages_stream__get_final_message_called__generator_tracked_correctly(
    fake_backend,
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
        model=MODEL_FOR_TESTS_FULL,
        messages=messages,
        max_tokens=10,
        system="You are a helpful assistant",
    )
    with message_stream_manager as stream:
        stream.get_final_message()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_stream",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_anthropic_messages_stream__get_final_message_called_after_stream_iteration_loop__generator_tracked_correctly_only_once(
    fake_backend,
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
        model=MODEL_FOR_TESTS_FULL,
        messages=messages,
        max_tokens=10,
        system="You are a helpful assistant",
    )
    with message_stream_manager as stream:
        for _ in stream:
            pass
        stream.get_final_message()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_stream",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_async_anthropic_messages_stream__data_tracked_correctly(
    fake_backend,
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
            model=MODEL_FOR_TESTS_FULL,
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        async with message_stream_manager as stream:
            async for _ in stream:
                pass

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_stream",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_async_anthropic_messages_stream__get_final_message_called_twice__data_tracked_correctly_once(
    fake_backend,
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
            model=MODEL_FOR_TESTS_FULL,
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
        )
        async with message_stream_manager as stream:
            await stream.get_final_message()
            await stream.get_final_message()

    asyncio.run(async_f())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_stream",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_anthropic_messages_create__stream_argument_is_True__Stream_object_returned__generations_tracked_correctly(
    fake_backend,
):
    client = anthropic.Anthropic()
    wrapped_client = track_anthropic(client)
    messages = [
        {
            "role": "user",
            "content": "Tell a short fact",
        }
    ]

    stream = wrapped_client.messages.create(
        model=MODEL_FOR_TESTS_FULL,
        messages=messages,
        max_tokens=10,
        system="You are a helpful assistant",
        stream=True,
    )
    for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_create",
        input={"messages": messages, "system": "You are a helpful assistant"},
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
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
                type="llm",
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@retry_on_internal_server_errors
def test_async_anthropic_messages_create__stream_argument_is_True__AsyncStream_object_returned__generations_tracked_correctly(
    fake_backend,
):
    async def async_f():
        client = anthropic.AsyncAnthropic()
        wrapped_client = track_anthropic(client)
        messages = [
            {
                "role": "user",
                "content": "Tell a short fact",
            }
        ]

        stream = await wrapped_client.messages.create(
            model=MODEL_FOR_TESTS_FULL,
            messages=messages,
            max_tokens=10,
            system="You are a helpful assistant",
            stream=True,
        )
        async for _ in stream:
            pass

    asyncio.run(async_f())
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="anthropic_messages_create",
        input={
            "messages": [
                {
                    "role": "user",
                    "content": "Tell a short fact",
                }
            ],
            "system": "You are a helpful assistant",
        },
        output={"content": ANY_LIST},
        tags=["anthropic"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="anthropic_messages_create",
                input={
                    "messages": [
                        {
                            "role": "user",
                            "content": "Tell a short fact",
                        }
                    ],
                    "system": "You are a helpful assistant",
                },
                output={"content": ANY_LIST},
                tags=["anthropic"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                type="llm",
                usage=EXPECTED_ANTHROPIC_USAGE_DICT,
                model=ANY_STRING.starting_with(MODEL_FOR_TESTS_SHORT),
                provider="anthropic",
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
