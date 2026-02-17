import os
from unittest import mock

import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


# TTS tests that call the real API are slow, skip unless explicitly enabled
SKIP_EXPENSIVE_TESTS = os.environ.get("OPIK_TEST_EXPENSIVE", "").lower() not in (
    "1",
    "true",
    "yes",
)


@pytest.fixture(autouse=True)
def check_openai_configured(ensure_openai_configured):
    pass


def test_openai_client_tts_create__error_handling(fake_backend):
    """
    Test error handling when TTS creation fails with invalid parameters.

    This is a fast test (no actual audio generation) that verifies:
    1. Error info is logged on both trace and span
    2. Trace and spans are finished gracefully despite the error
    3. Input parameters are correctly captured
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.audio.speech.create(
            model="invalid-tts-model",
            input="Hello, this is a test.",
            voice="alloy",
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": "Hello, this is a test.",
            "voice": "alloy",
        },
        output=None,
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        error_info={
            "exception_type": ANY_STRING,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio.speech.create",
                input={
                    "input": "Hello, this is a test.",
                    "voice": "alloy",
                },
                output=None,
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model="invalid-tts-model",
                provider="openai",
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.asyncio
async def test_openai_async_client_tts_create__error_handling(fake_backend):
    """
    Test async error handling when TTS creation fails with invalid parameters.

    This is a fast test (no actual audio generation) that verifies async error handling.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    with pytest.raises(openai.OpenAIError):
        _ = await wrapped_client.audio.speech.create(
            model="invalid-tts-model",
            input="Hello, this is a test.",
            voice="alloy",
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": "Hello, this is a test.",
            "voice": "alloy",
        },
        output=None,
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        error_info={
            "exception_type": ANY_STRING,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio.speech.create",
                input={
                    "input": "Hello, this is a test.",
                    "voice": "alloy",
                },
                output=None,
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model="invalid-tts-model",
                provider="openai",
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_client_tts_create__happyflow(fake_backend):
    """
    Test TTS audio.speech.create - the standard TTS workflow.

    This test verifies:
    1. Trace and span structure
    2. Input logging for TTS parameters (input text, voice, model)
    3. Metadata contains openai_tts type
    4. Model and provider are correctly populated
    5. Tags are applied correctly
    6. Output contains content_type info
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, this is a text-to-speech test."

    response = wrapped_client.audio.speech.create(
        model="tts-1",
        input=input_text,
        voice="alloy",
        response_format="mp3",
        speed=1.0,
    )

    # Verify we got audio content back
    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": "alloy",
        },
        output=ANY_DICT.containing({"content_type": ANY_BUT_NONE}),
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio.speech.create",
                input={
                    "input": input_text,
                    "voice": "alloy",
                },
                output=ANY_DICT.containing({"content_type": ANY_BUT_NONE}),
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                        "response_format": "mp3",
                        "speed": 1.0,
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model="tts-1",
                provider="openai",
                spans=[],
            ),
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_client_tts_with_streaming_response_create__happyflow(fake_backend):
    """
    Test TTS audio.speech.with_streaming_response.create workflow.

    This test verifies that the with_streaming_response pattern is tracked
    correctly. The with_streaming_response.create internally calls
    audio.speech.create, so our decorator on create fires.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, streaming TTS test."

    with wrapped_client.audio.speech.with_streaming_response.create(
        model="tts-1",
        input=input_text,
        voice="alloy",
    ) as response:
        # Read the stream to complete the request
        _ = response.read()

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": "alloy",
        },
        output=ANY_DICT.containing({"content_type": ANY_BUT_NONE}),
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio.speech.create",
                input={
                    "input": input_text,
                    "voice": "alloy",
                },
                output=ANY_DICT.containing({"content_type": ANY_BUT_NONE}),
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model="tts-1",
                provider="openai",
                spans=[],
            ),
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.asyncio
async def test_openai_async_client_tts_create__happyflow(fake_backend):
    """
    Test async TTS audio.speech.create workflow.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, async TTS test."

    response = await wrapped_client.audio.speech.create(
        model="tts-1",
        input=input_text,
        voice="alloy",
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": "alloy",
        },
        output=ANY_DICT.containing({"content_type": ANY_BUT_NONE}),
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
            }
        ),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio.speech.create",
                input={
                    "input": input_text,
                    "voice": "alloy",
                },
                output=ANY_DICT.containing({"content_type": ANY_BUT_NONE}),
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model="tts-1",
                provider="openai",
                spans=[],
            ),
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
