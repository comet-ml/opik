import pytest

import litellm
import litellm.types.utils

import opik
from opik.integrations.litellm import track_litellm
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


def test_litellm_completion_create__happyflow(fake_backend):
    """Test basic LiteLLM completion tracking."""
    track_litellm()

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    response = litellm.completion(
        model=MODEL_FOR_TESTS,
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
                usage=ANY_DICT,
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
async def test_litellm_acompletion_create__happyflow(fake_backend):
    """Test async LiteLLM completion tracking."""
    track_litellm()

    messages = [
        {"role": "system", "content": "You are a helpful assistant."},
        {"role": "user", "content": "Tell a fact"},
    ]

    response = await litellm.acompletion(
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
                usage=ANY_DICT,
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
    track_litellm()

    # This should cause an error due to invalid model
    with pytest.raises(Exception):
        litellm.completion(
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
    track_litellm()

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

    response = litellm.completion(
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
                usage=ANY_DICT,
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
