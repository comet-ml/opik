"""
Tests for OpenAI TTS (audio.speech) integration tracking.

Tests cover:
1. audio.speech.create (sync) — trace/span structure, metadata, usage
2. audio.speech.with_streaming_response.create — trace/span structure
3. Character-based usage tracking (input_characters → prompt_tokens)
4. Model and provider metadata
"""

import os

import openai
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

# TTS tests cost a small amount per call, skip unless explicitly enabled
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
def test_openai_audio_speech_create__happyflow(fake_backend):
    """
    Test audio.speech.create tracking.

    Verifies:
    1. Trace and span created with correct structure
    2. Input contains: input text, voice, model
    3. Usage tracks input_characters
    4. Metadata has created_from=openai, type=openai_tts
    5. Tags include openai and tts
    6. Model is correctly populated
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, this is a test of TTS tracking with Opik."

    response = wrapped_client.audio.speech.create(
        model="tts-1",
        voice="alloy",
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
            "voice": "alloy",
        },
        output=ANY_DICT,
        tags=["openai", "tts"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
                "input_characters": len(input_text),
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
                output=ANY_DICT,
                tags=["openai", "tts"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                        "input_characters": len(input_text),
                    }
                ),
                usage={
                    "prompt_tokens": len(input_text),
                    "total_tokens": len(input_text),
                    "input_characters": len(input_text),
                },
                model="tts-1",
                provider="openai",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
            )
        ],
    )

    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_audio_speech_with_streaming_response__happyflow(fake_backend):
    """
    Test audio.speech.with_streaming_response.create tracking.

    Verifies:
    1. Trace and span created with correct structure
    2. Input contains: input text, voice, model
    3. Usage tracks input_characters
    4. Metadata includes streaming=True
    5. Audio can be streamed without interference
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Streaming TTS test for Opik tracking."

    with wrapped_client.audio.speech.with_streaming_response.create(
        model="tts-1",
        voice="nova",
        input=input_text,
    ) as response:
        # Consume some of the stream to verify it works
        audio_bytes = b""
        for chunk in response.iter_bytes():
            audio_bytes += chunk

    assert len(audio_bytes) > 0, "Expected audio data from streaming response"

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.with_streaming_response.create",
        input={
            "input": input_text,
            "voice": "nova",
        },
        output=ANY_DICT,
        tags=["openai", "tts"],
        metadata=ANY_DICT.containing(
            {
                "created_from": "openai",
                "type": "openai_tts",
                "streaming": True,
                "input_characters": len(input_text),
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
                name="audio.speech.with_streaming_response.create",
                input={
                    "input": input_text,
                    "voice": "nova",
                },
                output=ANY_DICT,
                tags=["openai", "tts"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                        "streaming": True,
                        "input_characters": len(input_text),
                    }
                ),
                usage={
                    "prompt_tokens": len(input_text),
                    "total_tokens": len(input_text),
                    "input_characters": len(input_text),
                },
                model="tts-1",
                provider="openai",
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
            )
        ],
    )

    assert_equal(EXPECTED_TRACE, fake_backend.trace_trees[0])


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_audio_speech_create__tts_1_hd(fake_backend):
    """
    Test audio.speech.create with tts-1-hd model.
    Verifies model name is correctly captured in span.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing HD TTS model tracking."

    response = wrapped_client.audio.speech.create(
        model="tts-1-hd",
        voice="shimmer",
        input=input_text,
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    assert len(trace.spans) == 1
    span = trace.spans[0]
    assert span.model == "tts-1-hd"
    assert span.provider == "openai"


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_audio_speech_create__with_optional_params(fake_backend):
    """
    Test audio.speech.create with optional parameters (speed, response_format).
    Verifies all input parameters are captured.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing with optional parameters."

    response = wrapped_client.audio.speech.create(
        model="tts-1",
        voice="echo",
        input=input_text,
        speed=1.25,
        response_format="opus",
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace = fake_backend.trace_trees[0]
    assert len(trace.spans) == 1

    span_input = trace.spans[0].input
    assert span_input["input"] == input_text
    assert span_input["voice"] == "echo"
    assert span_input["speed"] == 1.25
    assert span_input["response_format"] == "opus"


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
def test_openai_audio_speech_create__character_count_usage(fake_backend):
    """
    Test that character count is accurately tracked in usage.
    Tests with known character counts.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    # Exactly 26 characters
    input_text = "abcdefghijklmnopqrstuvwxyz"

    response = wrapped_client.audio.speech.create(
        model="tts-1",
        voice="alloy",
        input=input_text,
    )

    assert response is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]

    assert span.usage["input_characters"] == 26
    assert span.usage["prompt_tokens"] == 26
    assert span.usage["total_tokens"] == 26
