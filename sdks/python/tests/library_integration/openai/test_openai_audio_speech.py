from typing import Any, Dict

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

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")

TTS_MODEL = "tts-1"
TTS_MODEL_HD = "tts-1-hd"


def _assert_metadata_contains_required_keys(metadata: Dict[str, Any]) -> None:
    REQUIRED_METADATA_KEYS = [
        "created_from",
        "type",
    ]
    for key in REQUIRED_METADATA_KEYS:
        assert key in metadata, f"Expected key '{key}' not found in metadata"


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-tts-integration-test", "openai-tts-integration-test"),
    ],
)
def test_openai_client_audio_speech_create__happyflow(
    fake_backend, project_name, expected_project_name
):
    """Test that audio.speech.create is tracked correctly."""
    client = openai.OpenAI()
    wrapped_client = track_openai(
        openai_client=client,
        project_name=project_name,
    )

    input_text = "Hello, this is a test of text to speech."

    # Call TTS API
    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL,
        voice="alloy",
        input=input_text,
    )

    # Consume the response to ensure tracking completes
    _ = response.content

    opik.flush_tracker()

    # Calculate expected character count for usage
    expected_character_count = len(input_text)

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio_speech_create",
        input=ANY_DICT.containing({"input": input_text, "voice": "alloy"}),
        output=ANY_DICT,
        tags=["openai", "tts"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio_speech_create",
                input=ANY_DICT.containing({"input": input_text, "voice": "alloy"}),
                output=ANY_DICT,
                tags=["openai", "tts"],
                metadata=ANY_DICT,
                usage={
                    "prompt_tokens": expected_character_count,
                    "completion_tokens": 0,
                    "total_tokens": expected_character_count,
                    "original_usage.character_count": expected_character_count,
                    "original_usage.total_tokens": expected_character_count,
                    "original_usage.prompt_tokens": expected_character_count,
                    "original_usage.completion_tokens": 0,
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=TTS_MODEL,
                provider="openai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_audio_speech_create__create_raises_an_error__span_and_trace_finished_gracefully(
    fake_backend,
):
    """Test that errors during TTS creation are handled gracefully."""
    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    with pytest.raises(openai.OpenAIError):
        # Invalid model should raise an error
        _ = wrapped_client.audio.speech.create(
            model="invalid-model",
            voice="alloy",
            input="Test",
        )

    opik.flush_tracker()

    # Verify trace was created even with error
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Verify error info is logged
    assert trace_tree.error_info is not None
    assert "exception_type" in trace_tree.error_info


def test_openai_client_audio_speech_create__in_tracked_function__span_attached_to_existing_trace(
    fake_backend,
):
    """Test that TTS calls within a tracked function are properly nested."""
    project_name = "openai-tts-integration-test"
    input_text = "Nested TTS test."

    @opik.track(project_name=project_name)
    def tts_wrapper() -> bytes:
        client = openai.OpenAI()
        wrapped_client = track_openai(client, project_name=project_name)

        response = wrapped_client.audio.speech.create(
            model=TTS_MODEL,
            voice="nova",
            input=input_text,
        )
        return response.content

    _ = tts_wrapper()
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    # Should have wrapper span with TTS span nested inside
    assert trace_tree.name == "tts_wrapper"
    assert len(trace_tree.spans) == 1

    wrapper_span = trace_tree.spans[0]
    assert len(wrapper_span.spans) == 1

    tts_span = wrapper_span.spans[0]
    assert tts_span.name == "audio_speech_create"
    assert tts_span.model == TTS_MODEL
    assert "tts" in tts_span.tags


def test_openai_client_audio_speech_create__different_models__model_logged_correctly(
    fake_backend,
):
    """Test that different TTS models are logged correctly."""
    client = openai.OpenAI()
    wrapped_client = track_openai(client)

    input_text = "Testing HD model."

    response = wrapped_client.audio.speech.create(
        model=TTS_MODEL_HD,
        voice="shimmer",
        input=input_text,
    )
    _ = response.content

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert len(trace_tree.spans) == 1
    tts_span = trace_tree.spans[0]
    assert tts_span.model == TTS_MODEL_HD


@pytest.mark.asyncio
async def test_async_openai_client_audio_speech_create__happyflow(
    fake_backend,
):
    """Test that async audio.speech.create is tracked correctly."""
    client = openai.AsyncOpenAI()
    wrapped_client = track_openai(client)

    input_text = "Hello from async TTS."

    response = await wrapped_client.audio.speech.create(
        model=TTS_MODEL,
        voice="alloy",
        input=input_text,
    )
    _ = response.content

    opik.flush_tracker()

    expected_character_count = len(input_text)

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert trace_tree.name == "audio_speech_create"
    assert len(trace_tree.spans) == 1

    tts_span = trace_tree.spans[0]
    assert tts_span.name == "audio_speech_create"
    assert tts_span.model == TTS_MODEL
    assert tts_span.provider == "openai"
    assert "tts" in tts_span.tags
    assert tts_span.usage["total_tokens"] == expected_character_count
