import os

import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from .constants import TTS_MODEL_FOR_TESTS
from ...testlib import (
    ANY,
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)

# TTS tests make real API calls, skip unless explicitly enabled
SKIP_EXPENSIVE_TESTS = os.environ.get("OPIK_TEST_EXPENSIVE", "").lower() not in (
    "1",
    "true",
    "yes",
)


@pytest.fixture(autouse=True)
def check_openai_configured(ensure_openai_configured):
    pass


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_client_tts_create__happyflow(fake_backend):
    """
    Test audio.speech.create - the main TTS generation workflow.

    This test verifies:
    1. Trace and span structure
    2. Input logging for TTS parameters (input text, voice, model)
    3. Metadata contains created_from and type fields
    4. Model and provider are correctly populated
    5. Tags are applied correctly
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, this is a test of text to speech."
    voice = "alloy"

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=voice,
        input=input_text,
    )

    # Verify we got audio content back
    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": voice,
        },
        output=ANY_DICT,
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
                    "voice": voice,
                },
                output=ANY_DICT,
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
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_client_tts_create_with_optional_params__happyflow(fake_backend):
    """
    Test audio.speech.create with optional parameters.

    This test verifies:
    1. Optional parameters (response_format, speed) are captured in input
    2. All TTS-specific parameters are logged correctly
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing optional parameters."
    voice = "echo"
    response_format = "opus"
    speed = 1.25

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=voice,
        input=input_text,
        response_format=response_format,
        speed=speed,
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": voice,
            "response_format": response_format,
            "speed": speed,
        },
        output=ANY_DICT,
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
                    "voice": voice,
                    "response_format": response_format,
                    "speed": speed,
                },
                output=ANY_DICT,
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
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


def test_openai_client_tts_create__error_handling(fake_backend):
    """
    Test error handling when TTS creation fails with invalid model.

    This is a fast test (no actual TTS generation) that verifies:
    1. Error info is logged on trace and span
    2. Trace and span are finished gracefully despite the error
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Test speech"
    voice = "alloy"

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.audio.speech.create(
            model="invalid-model-name",
            voice=voice,
            input=input_text,
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": voice,
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
                    "input": input_text,
                    "voice": voice,
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
                model="invalid-model-name",
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
@pytest.mark.asyncio
async def test_openai_async_client_tts_create__happyflow(fake_backend):
    """
    Test async audio.speech.create workflow.

    This test verifies that the async OpenAI client works correctly with TTS tracking.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, this is an async test of text to speech."
    voice = "alloy"

    response = await wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=voice,
        input=input_text,
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": voice,
        },
        output=ANY_DICT,
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
                    "voice": voice,
                },
                output=ANY_DICT,
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
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
                spans=[],
            ),
        ],
    )

    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


@pytest.mark.asyncio
async def test_openai_async_client_tts_create__error_handling(fake_backend):
    """
    Test async error handling when TTS creation fails with invalid model.

    This is a fast test (no actual TTS generation) that verifies async error handling.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Test speech"
    voice = "alloy"

    with pytest.raises(openai.OpenAIError):
        _ = await wrapped_client.audio.speech.create(
            model="invalid-model-name",
            voice=voice,
            input=input_text,
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": voice,
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
                    "input": input_text,
                    "voice": voice,
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
                model="invalid-model-name",
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
