"""Tests for OpenAI Text-to-Speech (TTS) tracking integration."""

import pytest
from unittest import mock
import openai

import opik
from opik.integrations.openai import track_openai


@pytest.fixture
def mock_tts_response():
    """Mock TTS API response (audio bytes)."""
    return b"fake_audio_content_bytes"


@pytest.fixture
def tracked_openai_client():
    """Create a tracked OpenAI client with mocked TTS."""
    client = openai.OpenAI(api_key="fake-api-key")
    tracked_client = track_openai(client, project_name="test-tts-project")
    return tracked_client


def test_tts_basic_tracking(tracked_openai_client, mock_tts_response, fake_backend):
    """Test basic TTS tracking with tts-1 model."""
    input_text = "Hello, this is a test of the text to speech system."
    
    with mock.patch.object(
        tracked_openai_client.audio.speech,
        "create",
        wraps=tracked_openai_client.audio.speech.create
    ) as mock_create:
        # Mock the actual API call
        mock_create.return_value = mock_tts_response
        
        # Make TTS call
        response = tracked_openai_client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input=input_text
        )
        
        assert response == mock_tts_response
    
    # Verify tracking
    opik.flush_tracker()
    
    # Check that span was created
    spans = fake_backend.span_writes
    assert len(spans) > 0
    
    # Find TTS span
    tts_span = None
    for span in spans:
        if span.get("name") == "tts_create":
            tts_span = span
            break
    
    assert tts_span is not None, "TTS span not found"
    
    # Verify span data
    assert tts_span["type"] == "llm"
    assert tts_span["model"] == "tts-1"
    assert tts_span["provider"] == "openai"
    
    # Verify input
    assert "input" in tts_span["input"]
    assert tts_span["input"]["input"] == input_text
    assert tts_span["input"]["voice"] == "alloy"
    
    # Verify metadata
    assert tts_span["metadata"]["type"] == "openai_tts"
    assert tts_span["metadata"]["model"] == "tts-1"
    assert tts_span["metadata"]["voice"] == "alloy"
    assert "tts" in tts_span["tags"]
    assert "openai" in tts_span["tags"]
    
    # Verify usage tracking
    assert "usage" in tts_span
    usage = tts_span["usage"]
    assert "original_usage.characters" in usage
    assert usage["original_usage.characters"] == len(input_text)


def test_tts_hd_model_tracking(tracked_openai_client, mock_tts_response, fake_backend):
    """Test TTS tracking with tts-1-hd model."""
    input_text = "Testing high definition text to speech."
    
    with mock.patch.object(
        tracked_openai_client.audio.speech,
        "create",
        wraps=tracked_openai_client.audio.speech.create
    ) as mock_create:
        mock_create.return_value = mock_tts_response
        
        response = tracked_openai_client.audio.speech.create(
            model="tts-1-hd",
            voice="nova",
            input=input_text,
            speed=1.25
        )
        
        assert response == mock_tts_response
    
    opik.flush_tracker()
    
    spans = fake_backend.span_writes
    tts_span = next((s for s in spans if s.get("name") == "tts_create"), None)
    
    assert tts_span is not None
    assert tts_span["model"] == "tts-1-hd"
    assert tts_span["metadata"]["voice"] == "nova"
    assert tts_span["metadata"]["speed"] == 1.25
    assert tts_span["usage"]["original_usage.characters"] == len(input_text)


def test_tts_character_count_calculation(tracked_openai_client, mock_tts_response, fake_backend):
    """Test that character count is calculated correctly for various inputs."""
    test_cases = [
        ("Short text", 10),
        ("A longer piece of text with multiple words and punctuation!", 61),
        ("Unicode: 你好世界 🌍", 14),  # Unicode characters
        ("", 0),  # Empty string
    ]
    
    for input_text, expected_length in test_cases:
        with mock.patch.object(
            tracked_openai_client.audio.speech,
            "create",
            wraps=tracked_openai_client.audio.speech.create
        ) as mock_create:
            mock_create.return_value = mock_tts_response
            
            tracked_openai_client.audio.speech.create(
                model="tts-1",
                voice="alloy",
                input=input_text
            )
        
        opik.flush_tracker()
        
        spans = fake_backend.span_writes
        tts_span = spans[-1]  # Get the most recent span
        
        assert tts_span["usage"]["original_usage.characters"] == expected_length


def test_tts_with_all_parameters(tracked_openai_client, mock_tts_response, fake_backend):
    """Test TTS tracking with all available parameters."""
    input_text = "Complete parameter test."
    
    with mock.patch.object(
        tracked_openai_client.audio.speech,
        "create",
        wraps=tracked_openai_client.audio.speech.create
    ) as mock_create:
        mock_create.return_value = mock_tts_response
        
        response = tracked_openai_client.audio.speech.create(
            model="tts-1",
            voice="echo",
            input=input_text,
            response_format="mp3",
            speed=0.75
        )
        
        assert response == mock_tts_response
    
    opik.flush_tracker()
    
    spans = fake_backend.span_writes
    tts_span = next((s for s in spans if s.get("name") == "tts_create"), None)
    
    assert tts_span is not None
    assert tts_span["input"]["voice"] == "echo"
    assert tts_span["input"]["response_format"] == "mp3"
    assert tts_span["input"]["speed"] == 0.75
    assert tts_span["metadata"]["response_format"] == "mp3"
    assert tts_span["metadata"]["speed"] == 0.75


@pytest.mark.asyncio
async def test_async_tts_tracking(mock_tts_response, fake_backend):
    """Test TTS tracking with async client."""
    client = openai.AsyncOpenAI(api_key="fake-api-key")
    tracked_client = track_openai(client, project_name="test-async-tts")
    
    input_text = "Async TTS test"
    
    with mock.patch.object(
        tracked_client.audio.speech,
        "create",
        wraps=tracked_client.audio.speech.create
    ) as mock_create:
        # Make the mock return a coroutine
        async def async_return():
            return mock_tts_response
        
        mock_create.return_value = async_return()
        
        response = await tracked_client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input=input_text
        )
        
        assert response == mock_tts_response
    
    opik.flush_tracker()
    
    spans = fake_backend.span_writes
    tts_span = next((s for s in spans if s.get("name") == "tts_create"), None)
    
    assert tts_span is not None
    assert tts_span["usage"]["original_usage.characters"] == len(input_text)
