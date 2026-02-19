import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


TTS_MODEL_FOR_TESTS = "tts-1"


@pytest.fixture(autouse=True)
def check_openai_configured(ensure_openai_configured):
    pass


def test_openai_client_audio_speech_create__happyflow(fake_backend):
    """
    Test audio.speech.create - the TTS endpoint.

    This test verifies:
    1. Trace and span structure
    2. Input logging for TTS parameters (input text, voice, etc.)
    3. Metadata contains created_from and type
    4. Model and provider are correctly populated
    5. Tags are applied correctly
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, this is a test of text to speech."

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice="alloy",
        input=input_text,
    )

    # Response should be binary audio content
    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": "alloy",
        },
        output={
            "content_type": ANY_STRING,
        },
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_audio",
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
                output={
                    "content_type": ANY_STRING,
                },
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_audio",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
                spans=[],
            )
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_openai_client_audio_speech_create__with_optional_params(fake_backend):
    """
    Test audio.speech.create with optional parameters (response_format, speed).

    Verifies that optional parameters are logged in inputs.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing with optional parameters."

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice="echo",
        input=input_text,
        response_format="opus",
        speed=1.25,
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": "echo",
            "response_format": "opus",
            "speed": 1.25,
        },
        output={
            "content_type": ANY_STRING,
        },
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_audio",
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
                    "voice": "echo",
                    "response_format": "opus",
                    "speed": 1.25,
                },
                output={
                    "content_type": ANY_STRING,
                },
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_audio",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
                spans=[],
            )
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


@pytest.mark.asyncio
async def test_openai_async_client_audio_speech_create__happyflow(fake_backend):
    """
    Test async audio.speech.create - the TTS endpoint.

    Verifies that the async OpenAI client works correctly with audio tracking.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello from async TTS."

    response = await wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice="alloy",
        input=input_text,
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={
            "input": input_text,
            "voice": "alloy",
        },
        output={
            "content_type": ANY_STRING,
        },
        tags=["openai"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_audio",
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
                output={
                    "content_type": ANY_STRING,
                },
                tags=["openai"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_audio",
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
                spans=[],
            )
        ],
    )

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
