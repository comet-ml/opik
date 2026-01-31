"""
Tests for OpenAI TTS (Text-to-Speech) tracking integration.

These tests verify that the Opik integration correctly tracks:
- audio.speech.create() calls
- audio.speech.with_streaming_response.create() calls
- Input text and parameters
- Model and voice metadata
- Character count for cost estimation
"""

import os
import tempfile

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

# TTS model for testing - use the cheapest option
TTS_MODEL_FOR_TESTS = "tts-1"
TTS_VOICE_FOR_TESTS = "alloy"

# TTS tests use real API calls which cost money, skip unless explicitly enabled
SKIP_EXPENSIVE_TESTS = os.environ.get("OPIK_TEST_EXPENSIVE", "").lower() not in (
    "1",
    "true",
    "yes",
)


# =============================================================================
# Tests that require real OpenAI API calls (expensive)
# =============================================================================


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_client_audio_speech_create__happyflow(fake_backend):
    """
    Test audio.speech.create - the main TTS method.

    This test verifies:
    1. Trace and span structure
    2. Input logging (input text and voice)
    3. Metadata contains input_characters for cost calculation
    4. Model and provider are correctly populated
    5. Tags are applied correctly
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Hello, this is a test of the text to speech API."

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=TTS_VOICE_FOR_TESTS,
        input=input_text,
    )

    # Verify we got audio content back
    assert response.content is not None
    assert len(response.content) > 0

    opik.flush_tracker()

    # Verify trace structure
    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.create",
        input={"input": input_text, "voice": TTS_VOICE_FOR_TESTS},
        output=ANY_DICT.containing({"status": "completed"}),
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
                input={"input": input_text, "voice": TTS_VOICE_FOR_TESTS},
                output=ANY_DICT.containing({"status": "completed"}),
                tags=["openai", "tts"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                        "input_characters": len(input_text),
                    }
                ),
                usage=None,  # TTS doesn't return token usage
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
            )
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE, trace_tree)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_client_audio_speech_create__with_custom_project_name(fake_backend):
    """
    Test that custom project_name is correctly applied to TTS spans.
    """
    client = openai.OpenAI()
    custom_project = "tts-integration-test"
    wrapped_client = track_openai(openai_client=client, project_name=custom_project)

    input_text = "Testing custom project name."

    _ = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=TTS_VOICE_FOR_TESTS,
        input=input_text,
    )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert trace_tree.project_name == custom_project
    assert trace_tree.spans[0].project_name == custom_project


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_client_audio_speech_create__with_optional_parameters(fake_backend):
    """
    Test that optional parameters (response_format, speed) are tracked in metadata.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing optional parameters."

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=TTS_VOICE_FOR_TESTS,
        input=input_text,
        response_format="mp3",
        speed=1.0,
    )

    assert response.content is not None

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Check that optional parameters are in metadata
    metadata = trace_tree.spans[0].metadata
    assert metadata.get("response_format") == "mp3"
    assert metadata.get("speed") == 1.0


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_client_audio_speech_create__write_to_file(fake_backend):
    """
    Test TTS with writing output to file.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "This audio will be saved to a file."

    with tempfile.TemporaryDirectory() as temp_dir:
        output_path = os.path.join(temp_dir, "speech.mp3")

        response = wrapped_client.audio.speech.create(
            model=TTS_MODEL_FOR_TESTS,
            voice=TTS_VOICE_FOR_TESTS,
            input=input_text,
        )

        # Write to file
        response.write_to_file(output_path)

        # Verify file was created
        assert os.path.exists(output_path)
        assert os.path.getsize(output_path) > 0

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_client_audio_speech_with_streaming_response__happyflow(fake_backend):
    """
    Test audio.speech.with_streaming_response.create for streaming TTS.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing streaming response for TTS."

    with wrapped_client.audio.speech.with_streaming_response.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=TTS_VOICE_FOR_TESTS,
        input=input_text,
    ) as response:
        # Read the streamed content
        content = response.read()
        assert len(content) > 0

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio.speech.with_streaming_response.create",
        input={"input": input_text, "voice": TTS_VOICE_FOR_TESTS},
        output=ANY_DICT.containing({"status": "stream_initiated"}),
        tags=["openai", "tts", "streaming"],
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
                input={"input": input_text, "voice": TTS_VOICE_FOR_TESTS},
                output=ANY_DICT.containing({"status": "stream_initiated"}),
                tags=["openai", "tts", "streaming"],
                metadata=ANY_DICT.containing(
                    {
                        "created_from": "openai",
                        "type": "openai_tts",
                        "streaming": True,
                        "input_characters": len(input_text),
                    }
                ),
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=TTS_MODEL_FOR_TESTS,
                provider="openai",
            )
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE, trace_tree)


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
def test_openai_client_audio_speech__nested_in_tracked_function(fake_backend):
    """
    Test that TTS calls work correctly when nested inside other tracked functions.
    """
    client = openai.OpenAI()
    wrapped_client = track_openai(openai_client=client)

    @opik.track(name="generate_audio_response")
    def generate_audio_response(text: str) -> bytes:
        response = wrapped_client.audio.speech.create(
            model=TTS_MODEL_FOR_TESTS,
            voice=TTS_VOICE_FOR_TESTS,
            input=text,
        )
        return response.content

    input_text = "This is nested inside a tracked function."
    audio_content = generate_audio_response(input_text)

    assert len(audio_content) > 0

    opik.flush_tracker()

    # Should have one trace with nested spans
    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.name == "generate_audio_response"

    # The TTS call should be a child span
    assert len(trace_tree.spans) == 1
    parent_span = trace_tree.spans[0]
    assert parent_span.name == "generate_audio_response"

    # TTS span should be nested under the parent
    assert len(parent_span.spans) == 1
    tts_span = parent_span.spans[0]
    assert tts_span.name == "audio.speech.create"
    assert tts_span.model == TTS_MODEL_FOR_TESTS


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
@pytest.mark.usefixtures("ensure_openai_configured")
@pytest.mark.asyncio
async def test_async_openai_client_audio_speech_create__happyflow(fake_backend):
    """
    Test async audio.speech.create method.
    """
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(openai_client=client)

    input_text = "Testing async TTS API."

    response = await wrapped_client.audio.speech.create(
        model=TTS_MODEL_FOR_TESTS,
        voice=TTS_VOICE_FOR_TESTS,
        input=input_text,
    )

    assert response.content is not None
    assert len(response.content) > 0

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert trace_tree.name == "audio.speech.create"
    assert trace_tree.spans[0].model == TTS_MODEL_FOR_TESTS
    assert "tts" in trace_tree.tags


# =============================================================================
# Tests that do NOT require real OpenAI API calls (can run without credentials)
# =============================================================================


def test_openai_client_track_openai__already_tracked_client_not_patched_twice():
    """
    Test that calling track_openai twice on the same client doesn't double-patch.
    """
    # Use dummy api key to avoid needing real credentials
    client = openai.OpenAI(api_key="dummy")

    wrapped_client_1 = track_openai(openai_client=client)
    wrapped_client_2 = track_openai(openai_client=wrapped_client_1)

    # Should be the same object
    assert wrapped_client_1 is wrapped_client_2


def test_openai_client_audio_speech_has_expected_attributes():
    """
    Test that the OpenAI client has the expected audio.speech attributes.
    """
    # Use dummy api key to avoid needing real credentials
    client = openai.OpenAI(api_key="dummy")
    wrapped_client = track_openai(openai_client=client)

    assert hasattr(wrapped_client, "audio")
    assert hasattr(wrapped_client.audio, "speech")
    assert hasattr(wrapped_client.audio.speech, "create")
    assert hasattr(wrapped_client.audio.speech, "with_streaming_response")
    assert hasattr(wrapped_client.audio.speech.with_streaming_response, "create")


def test_openai_async_client_audio_speech_has_expected_attributes():
    """
    Test that the async OpenAI client has the expected audio.speech attributes.
    """
    # Use dummy api key to avoid needing real credentials
    client = openai.AsyncOpenAI(api_key="dummy")
    wrapped_client = track_openai(openai_client=client)

    assert hasattr(wrapped_client, "audio")
    assert hasattr(wrapped_client.audio, "speech")
    assert hasattr(wrapped_client.audio.speech, "create")
    assert hasattr(wrapped_client.audio.speech, "with_streaming_response")
    assert hasattr(wrapped_client.audio.speech.with_streaming_response, "create")
