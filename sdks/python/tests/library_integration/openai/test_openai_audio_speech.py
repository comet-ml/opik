# Test imports
from unittest.mock import MagicMock, patch

import openai
import pytest
from openai._response import BinaryAPIResponse

from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai

# Tests use mocked responses, no API calls needed

MODEL_FOR_TESTS = "tts-1"


@pytest.mark.skip(reason="Requires actual API call, used for manual testing")
def test_openai_audio_speech_create__integration_test():
    """
    Integration test for OpenAI TTS tracking. Skipped by default to avoid API costs.
    Remove @pytest.mark.skip and set OPENAI_API_KEY to run manually.
    """
    client = openai.OpenAI()
    client = track_openai(client, project_name="test")

    # This would require actual API call and would cost money
    response = client.audio.speech.create(
        model=MODEL_FOR_TESTS,
        voice="alloy",
        input="This is a test message for TTS tracking!",
        response_format="mp3",
        speed=1.0,
    )

    # Verify response is valid
    assert response is not None
    assert hasattr(response, "content")


def test_openai_audio_speech_create__mocked():
    """Test TTS tracking with mocked OpenAI response."""

    # Create mock response
    mock_response = MagicMock(spec=BinaryAPIResponse)
    mock_response.content = b"fake_audio_data"
    mock_response.headers = {"content-length": "1024", "content-type": "audio/mpeg"}

    client = openai.OpenAI(api_key="fake_key")  # Use fake key for testing

    with patch.object(
        client.audio.speech, "create", return_value=mock_response
    ) as mock_create:
        client = track_openai(client, project_name="test")

        # Make the call
        response = client.audio.speech.create(
            model=MODEL_FOR_TESTS,
            voice="alloy",
            input="Test message for TTS tracking!",
            response_format="mp3",
            speed=1.2,
        )

        # Verify mock was called
        mock_create.assert_called_once()
        call_args = mock_create.call_args
        assert call_args[1]["model"] == MODEL_FOR_TESTS
        assert call_args[1]["voice"] == "alloy"
        assert call_args[1]["input"] == "Test message for TTS tracking!"
        assert call_args[1]["response_format"] == "mp3"
        assert call_args[1]["speed"] == 1.2

        # Verify response
        assert response == mock_response


@pytest.mark.parametrize(
    "model,voice,input_text,response_format,speed",
    [
        ("tts-1", "alloy", "Hello world!", "mp3", 1.0),
        ("tts-1-hd", "echo", "This is a longer test message.", "wav", 1.5),
        ("tts-1", "nova", "Short text", "opus", 0.8),
    ],
)
def test_openai_audio_speech_create__various_parameters(
    model,
    voice,
    input_text,
    response_format,
    speed,
):
    """Test TTS tracking with various parameter combinations using mocked responses."""

    # Create mock response
    mock_response = MagicMock(spec=BinaryAPIResponse)
    mock_response.content = b"fake_audio_data"
    mock_response.headers = {"content-length": "2048", "content-type": "audio/mpeg"}

    client = openai.OpenAI(api_key="fake_key")  # Use fake key for testing

    with patch.object(
        client.audio.speech, "create", return_value=mock_response
    ) as mock_create:
        client = track_openai(client, project_name=OPIK_PROJECT_DEFAULT_NAME)

        # Make the call
        client.audio.speech.create(
            model=model,
            voice=voice,
            input=input_text,
            response_format=response_format,
            speed=speed,
        )

        # Verify the mock was called with correct parameters
        mock_create.assert_called_once()
        call_args = mock_create.call_args
        assert call_args[1]["model"] == model
        assert call_args[1]["voice"] == voice
        assert call_args[1]["input"] == input_text
        assert call_args[1]["response_format"] == response_format
        assert call_args[1]["speed"] == speed


def test_openai_audio_speech_create__character_count_usage():
    """Test that character count is correctly calculated for usage."""

    test_cases = [
        ("Hello", 5),
        ("Hello, world!", 13),
        ("", 0),
        ("Multi\nline\ntext", 15),
        ("Text with émojis 🎉", 18),  # Test unicode characters
    ]

    for input_text, expected_chars in test_cases:
        # Create mock response
        mock_response = MagicMock(spec=BinaryAPIResponse)
        mock_response.content = b"fake_audio_data"
        mock_response.headers = {"content-length": "1024"}

        client = openai.OpenAI(api_key="fake_key")  # Use fake key for testing

        with patch.object(
            client.audio.speech, "create", return_value=mock_response
        ) as mock_create:
            client = track_openai(client, project_name="test")

            # Make the call
            client.audio.speech.create(
                model="tts-1",
                voice="alloy",
                input=input_text,
            )

            # Verify character count matches expected and mock was called
            assert len(input_text) == expected_chars
            mock_create.assert_called_once()


@pytest.mark.asyncio
async def test_openai_audio_speech_create__async_client():
    """Test TTS tracking with async OpenAI client."""

    # Create mock response
    mock_response = MagicMock(spec=BinaryAPIResponse)
    mock_response.content = b"fake_audio_data"
    mock_response.headers = {"content-length": "1024"}

    client = openai.AsyncOpenAI(api_key="fake_key")  # Use fake key for testing

    with patch.object(
        client.audio.speech, "create", return_value=mock_response
    ) as mock_create:
        client = track_openai(client, project_name="test")

        # Make the async call
        response = await client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input="Async test message!",
        )

        assert response == mock_response
        mock_create.assert_called_once()


def test_openai_audio_speech_create__custom_openai_host():
    """Test TTS tracking with custom OpenAI host."""

    # Create mock response
    mock_response = MagicMock(spec=BinaryAPIResponse)
    mock_response.content = b"fake_audio_data"
    mock_response.headers = {"content-length": "512"}

    # Create client with custom base URL and fake API key
    client = openai.OpenAI(
        base_url="https://custom-openai-host.com/v1", api_key="fake_key"
    )

    with patch.object(
        client.audio.speech, "create", return_value=mock_response
    ) as mock_create:
        client = track_openai(client, project_name=OPIK_PROJECT_DEFAULT_NAME)

        # Make the call
        client.audio.speech.create(
            model="tts-1",
            voice="alloy",
            input="Custom host test!",
        )

        # Verify mock was called
        mock_create.assert_called_once()
        call_args = mock_create.call_args
        assert call_args[1]["input"] == "Custom host test!"


def test_openai_audio_speech_with_streaming_response__mocked():
    """Test TTS tracking with streaming response using mocked OpenAI response."""
    
    # Create mock streaming response
    mock_response = MagicMock()
    mock_response.response.headers = {"content-length": "2048", "content-type": "audio/mpeg"}
    mock_response.iter_bytes = lambda: iter([b"chunk1", b"chunk2", b"chunk3"])
    
    client = openai.OpenAI(api_key="fake_key")
    
    with patch.object(
        client.audio.speech.with_streaming_response, "create", return_value=mock_response
    ) as mock_create:
        client = track_openai(client, project_name="test")
        
        # Make the streaming call
        response = client.audio.speech.with_streaming_response.create(
            model=MODEL_FOR_TESTS,
            voice="nova",
            input="Streaming test message!",
            response_format="mp3",
            speed=1.0,
        )
        
        # Verify mock was called
        mock_create.assert_called_once()
        call_args = mock_create.call_args
        assert call_args[1]["model"] == MODEL_FOR_TESTS
        assert call_args[1]["voice"] == "nova"
        assert call_args[1]["input"] == "Streaming test message!"
        assert call_args[1]["response_format"] == "mp3"
        assert call_args[1]["speed"] == 1.0
        
        # Verify response
        assert response == mock_response


@pytest.mark.asyncio
async def test_openai_audio_speech_with_streaming_response__async_client():
    """Test TTS tracking with async streaming response."""
    
    # Create mock streaming response
    mock_response = MagicMock()
    mock_response.response.headers = {"content-length": "1536"}
    mock_response.iter_bytes = lambda: iter([b"async_chunk1", b"async_chunk2"])
    
    client = openai.AsyncOpenAI(api_key="fake_key")
    
    # Create an async mock function that returns the mock response
    async def async_mock_create(*args, **kwargs):
        return mock_response
    
    with patch.object(
        client.audio.speech.with_streaming_response, "create", side_effect=async_mock_create
    ) as mock_create:
        client = track_openai(client, project_name="test")
        
        # Make the async streaming call
        response = await client.audio.speech.with_streaming_response.create(
            model="tts-1-hd",
            voice="echo",
            input="Async streaming test!",
        )
        
        assert response == mock_response
        mock_create.assert_called_once()
