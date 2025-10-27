import pytest

import litellm
import litellm.types.utils

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
def test_litellm_completion_create__happyflow(fake_backend, model, expected_provider):
    """Test basic LiteLLM completion tracking."""
    tracked_completion = track_completion()(litellm.completion)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    response = tracked_completion(
        model=model,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert isinstance(response, litellm.types.utils.ModelResponse)

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": messages},
        output={"choices": ANY_LIST},
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
                output={"choices": ANY_LIST},
                tags=["litellm"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
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
async def test_litellm_acompletion_create__happyflow(fake_backend):
    """Test async LiteLLM completion tracking."""
    tracked_acompletion = track_completion()(litellm.acompletion)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    response = await tracked_acompletion(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert isinstance(response, litellm.types.utils.ModelResponse)

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="acompletion",
        input={"messages": messages},
        output={"choices": ANY_LIST},
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
                output={"choices": ANY_LIST},
                tags=["litellm"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,  # Cost calculated by LiteLLM
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING,
                provider="openai",  # Actual LLM provider, not "litellm"
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_completion_error_handling__exception_logged(fake_backend):
    """Test error handling in LiteLLM completion tracking."""
    tracked_completion = track_completion()(litellm.completion)

    # This should cause an error due to invalid model
    with pytest.raises(Exception):
        tracked_completion(
            model="invalid-model-name",
            messages=[{"role": "user", "content": "Test"}],
        )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": [{"role": "user", "content": "Test"}]},
        output=None,
        tags=["litellm"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "litellm",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="completion",
                input={"messages": [{"role": "user", "content": "Test"}]},
                output=None,
                tags=["litellm"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                    }
                ),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                error_info=ANY_BUT_NONE,
                spans=[],
                model="invalid-model-name",
                provider=None,  # Provider is None for invalid model
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_completion_with_tools__tools_logged(fake_backend):
    """Test LiteLLM completion tracking with tools/function calling."""
    tracked_completion = track_completion()(litellm.completion)

    messages = [
        {"role": "user", "content": "What's the weather like?"},
    ]

    tools = [
        {
            "type": "function",
            "function": {
                "name": "get_weather",
                "description": "Get the current weather",
                "parameters": {
                    "type": "object",
                    "properties": {"location": {"type": "string"}},
                },
            },
        }
    ]

    response = tracked_completion(
        model=MODEL_FOR_TESTS,
        messages=messages,
        tools=tools,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert isinstance(response, litellm.types.utils.ModelResponse)

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": messages, "tools": tools},
        output={"choices": ANY_LIST},
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
                input={"messages": messages, "tools": tools},
                output={"choices": ANY_LIST},
                tags=["litellm"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
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


def test_litellm_completion_create__opik_args__happyflow(fake_backend):
    """Test basic LiteLLM completion tracking with opik_args."""
    tracked_completion = track_completion()(litellm.completion)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    args_dict = {
        "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
        "trace": {
            "thread_id": "conversation-2",
            "tags": ["trace_tag"],
            "metadata": {"trace_key": "trace_value"},
        },
    }

    response = tracked_completion(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        opik_args=args_dict,
    )

    opik.flush_tracker()

    assert isinstance(response, litellm.types.utils.ModelResponse)

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="completion",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["litellm", "span_tag", "trace_tag"],
        metadata=ANY_DICT.containing(
            {"created_from": "litellm", "max_tokens": 10, "trace_key": "trace_value"}
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        thread_id="conversation-2",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="completion",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["litellm", "span_tag"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                        "span_key": "span_value",
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,  # Cost calculated by LiteLLM
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING,
                provider="openai",  # Actual LLM provider, not "litellm"
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.asyncio
async def test_litellm_acompletion_create__opik_args__happyflow(fake_backend):
    """Test async LiteLLM completion tracking with opik_args."""
    tracked_acompletion = track_completion()(litellm.acompletion)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    args_dict = {
        "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
        "trace": {
            "thread_id": "conversation-2",
            "tags": ["trace_tag"],
            "metadata": {"trace_key": "trace_value"},
        },
    }

    response = await tracked_acompletion(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
        opik_args=args_dict,
    )

    opik.flush_tracker()

    assert isinstance(response, litellm.types.utils.ModelResponse)

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="acompletion",
        input={"messages": messages},
        output={"choices": ANY_LIST},
        tags=["litellm", "span_tag", "trace_tag"],
        metadata=ANY_DICT.containing(
            {"created_from": "litellm", "max_tokens": 10, "trace_key": "trace_value"}
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        thread_id="conversation-2",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="acompletion",
                input={"messages": messages},
                output={"choices": ANY_LIST},
                tags=["litellm", "span_tag"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "litellm",
                        "max_tokens": 10,
                        "span_key": "span_value",
                    }
                ),
                usage=constants.EXPECTED_LITELLM_USAGE_LOGGED_FORMAT,
                total_cost=ANY_BUT_NONE,  # Cost calculated by LiteLLM
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                model=ANY_STRING,
                provider="openai",  # Actual LLM provider, not "litellm"
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_litellm_completion_double_decoration__idempotent(fake_backend):
    """Test that double decoration doesn't create double wrapping."""
    # First decoration
    tracked_completion_1 = track_completion()(litellm.completion)
    # Second decoration of the SAME wrapped function
    tracked_completion_2 = track_completion()(tracked_completion_1)

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    response = tracked_completion_2(
        model=MODEL_FOR_TESTS,
        messages=messages,
        max_tokens=10,
    )

    opik.flush_tracker()

    assert isinstance(response, litellm.types.utils.ModelResponse)

    # Should only create ONE trace, not nested traces
    assert len(fake_backend.trace_trees) == 1

    trace = fake_backend.trace_trees[0]
    # Should have exactly one span, not nested spans
    assert len(trace.spans) == 1
    # The span should not have any nested spans
    assert len(trace.spans[0].spans) == 0
