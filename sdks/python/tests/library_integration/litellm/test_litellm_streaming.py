import pytest

import litellm
import litellm.litellm_core_utils.streaming_handler

import opik
from opik.integrations.litellm import track_completion
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)

from . import constants

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")

MODEL_FOR_TESTS = constants.MODEL_FOR_TESTS


@pytest.mark.parametrize("model,expected_provider", constants.TEST_MODELS_PARAMETRIZE)
def test_litellm_completion_streaming__happyflow(
    fake_backend, model, expected_provider
):
    """Test basic LiteLLM streaming completion tracking."""
    tracked_completion = track_completion()(litellm.completion)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Say hello in one word"},
    ]

    stream = tracked_completion(
        model=model,
        messages=messages,
        max_tokens=10,
        stream=True,
        stream_options={"include_usage": True},
    )

    # Consume the stream
    chunks_count = 0
    full_text = ""
    for chunk in stream:
        chunks_count += 1
        if chunk.choices and chunk.choices[0].delta.content:
            full_text += chunk.choices[0].delta.content

    opik.flush_tracker()

    # Verify we got chunks
    assert chunks_count > 0, "Should have received streaming chunks"
    assert len(full_text) > 0, "Should have received text content"

    # Verify the trace structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": messages},
        output={"choices": ANY_LIST},  # Aggregated output
        tags=["litellm"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "litellm",
                "max_tokens": 10,
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="completion",
                input={"messages": messages},
                output={"choices": ANY_LIST},  # Aggregated output
                tags=["litellm"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,  # Usage info must be present
                total_cost=ANY_BUT_NONE,  # Cost calculated by LiteLLM
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING,
                provider=expected_provider,
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.asyncio
async def test_litellm_acompletion_streaming__happyflow(fake_backend):
    """Test async LiteLLM streaming completion tracking."""
    tracked_acompletion = track_completion()(litellm.acompletion)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Say hello in one word"},
    ]

    stream = await tracked_acompletion(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        stream=True,
        stream_options={"include_usage": True},
    )

    # Consume the stream
    chunks_count = 0
    full_text = ""
    async for chunk in stream:
        chunks_count += 1
        if chunk.choices and chunk.choices[0].delta.content:
            full_text += chunk.choices[0].delta.content

    opik.flush_tracker()

    # Verify we got chunks
    assert chunks_count > 0, "Should have received streaming chunks"
    assert len(full_text) > 0, "Should have received text content"

    # Verify the trace structure
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="acompletion",
        input={"messages": messages},
        output={"choices": ANY_LIST},  # Aggregated output
        tags=["litellm"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "litellm",
                "max_tokens": 10,
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="acompletion",
                input={"messages": messages},
                output={"choices": ANY_LIST},  # Aggregated output
                tags=["litellm"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,  # Usage info must be present
                total_cost=ANY_BUT_NONE,  # Cost calculated by LiteLLM
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING,
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_completion_streaming_with_opik_args__happyflow(fake_backend):
    """Test LiteLLM streaming with custom opik_args."""
    tracked_completion = track_completion()(litellm.completion)

    messages = [
        {"role": "user", "content": "Hello"},
    ]

    args_dict = {
        "span": {
            "tags": ["streaming-span"],
            "metadata": {"stream_key": "stream_value"},
        },
        "trace": {
            "thread_id": "stream-thread-1",
            "tags": ["streaming-trace"],
            "metadata": {"trace_key": "trace_value"},
        },
    }

    stream = tracked_completion(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        stream=True,
        stream_options={"include_usage": True},
        opik_args=args_dict,
    )

    # Consume the stream
    for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["litellm", "streaming-span", "streaming-trace"],
        metadata=ANY_DICT.containing(
            {"created_from": "litellm", "max_tokens": 10, "trace_key": "trace_value"}
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        thread_id="stream-thread-1",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="completion",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["litellm", "streaming-span"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                        "stream_key": "stream_value",
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,  # Cost calculated by LiteLLM
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING,
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
