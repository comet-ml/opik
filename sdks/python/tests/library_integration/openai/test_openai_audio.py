"""Tests for OpenAI audio (TTS and transcriptions) tracking."""

import io
import os
from unittest import mock

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

# Audio tests call the real API and cost money, skip unless explicitly enabled
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
class TestAudioSpeechTracking:
    """Tests for audio.speech.create() (TTS) tracking."""

    def test_audio_speech_create__happyflow(self, fake_backend):
        client = openai.OpenAI()
        wrapped_client = track_openai(openai_client=client)

        response = wrapped_client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input="Hello, this is a test of text to speech.",
        )

        # Response should be usable
        assert response is not None
        content = response.content
        assert len(content) > 0

        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 1

        trace = fake_backend.trace_trees[0]
        assert trace.span_trees is not None
        assert len(trace.span_trees) == 1

        span = trace.span_trees[0]
        assert span.name == "audio.speech.create"
        assert span.model == "tts-1"
        assert span.tags == ["openai", "audio", "tts"]

        # Check input was logged
        assert "input" in span.input
        assert span.input["voice"] == "alloy"

        # Check metadata
        assert span.metadata["created_from"] == "openai"
        assert span.metadata["type"] == "openai_audio_speech"
        assert "input_text_length" in span.metadata
        assert span.metadata["input_text_length"] == len(
            "Hello, this is a test of text to speech."
        )

        # Check output has audio info
        assert "status_code" in span.output
        assert span.output["status_code"] == 200

    def test_audio_speech_create__with_project_name(self, fake_backend):
        client = openai.OpenAI()
        wrapped_client = track_openai(
            openai_client=client,
            project_name="tts-test-project",
        )

        response = wrapped_client.audio.speech.create(
            model="tts-1",
            voice="nova",
            input="Short test.",
            response_format="mp3",
            speed=1.0,
        )

        assert response is not None

        opik.flush_tracker()

        assert len(fake_backend.trace_trees) == 1
        trace = fake_backend.trace_trees[0]
        assert trace.span_trees[0].input.get("response_format") == "mp3"
        assert trace.span_trees[0].input.get("speed") == 1.0


@pytest.mark.skipif(
    SKIP_EXPENSIVE_TESTS,
    reason="Expensive tests disabled. Set OPIK_TEST_EXPENSIVE=1 to enable.",
)
class TestAudioTranscriptionsTracking:
    """Tests for audio.transcriptions.create() tracking."""

    def test_audio_transcriptions_create__happyflow(self, fake_backend):
        client = openai.OpenAI()
        wrapped_client = track_openai(openai_client=client)

        # First generate some audio to transcribe
        speech_response = wrapped_client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input="Hello world, this is a transcription test.",
        )

        audio_content = speech_response.content

        # Now transcribe it
        audio_file = io.BytesIO(audio_content)
        audio_file.name = "test_audio.mp3"

        transcription = wrapped_client.audio.transcriptions.create(
            model="whisper-1",
            file=audio_file,
        )

        assert transcription is not None

        opik.flush_tracker()

        # Should have 2 traces: one for speech, one for transcription
        assert len(fake_backend.trace_trees) == 2

        # Find the transcription trace
        transcription_trace = None
        for trace in fake_backend.trace_trees:
            if (
                trace.span_trees
                and trace.span_trees[0].name == "audio.transcriptions.create"
            ):
                transcription_trace = trace
                break

        assert transcription_trace is not None
        span = transcription_trace.span_trees[0]
        assert span.name == "audio.transcriptions.create"
        assert span.model == "whisper-1"
        assert span.tags == ["openai", "audio", "transcription"]
        assert span.metadata["created_from"] == "openai"
        assert span.metadata["type"] == "openai_audio_transcription"

        # Output should contain transcribed text
        assert "text" in span.output
