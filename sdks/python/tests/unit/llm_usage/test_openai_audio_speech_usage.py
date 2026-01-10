import pydantic
import pytest

from opik.llm_usage.openai_audio_speech_usage import OpenAIAudioSpeechUsage


def test_openai_audio_speech_usage_creation__happyflow():
    usage_data = {
        "character_count": 150,
        "total_tokens": 150,
        "prompt_tokens": 150,
        "completion_tokens": 0,
    }
    usage = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_data)
    assert usage.character_count == 150
    assert usage.total_tokens == 150
    assert usage.prompt_tokens == 150
    assert usage.completion_tokens == 0


def test_openai_audio_speech_usage_creation__character_count_required():
    """Test that character_count is required and KeyError is raised without it."""
    usage_data = {
        "total_tokens": 200,
        "prompt_tokens": 200,
        "completion_tokens": 0,
    }
    with pytest.raises(KeyError, match="character_count is required"):
        OpenAIAudioSpeechUsage.from_original_usage_dict(usage_data)


def test_openai_audio_speech_usage_creation__minimal_data():
    """Test with minimal data - just character_count."""
    usage_data = {
        "character_count": 100,
    }
    usage = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_data)
    assert usage.character_count == 100
    assert usage.total_tokens == 100  # defaults to character_count
    assert usage.prompt_tokens == 100  # defaults to character_count
    assert usage.completion_tokens == 0


def test_openai_audio_speech_usage__to_backend_compatible_flat_dict__happyflow():
    usage_data = {
        "character_count": 150,
        "total_tokens": 150,
        "prompt_tokens": 150,
        "completion_tokens": 0,
    }
    usage = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_data)
    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.character_count": 150,
        "original_usage.total_tokens": 150,
        "original_usage.prompt_tokens": 150,
        "original_usage.completion_tokens": 0,
    }


def test_openai_audio_speech_usage__invalid_data_passed__validation_error_is_raised():
    usage_data = {
        "character_count": "invalid",
        "total_tokens": None,
    }
    with pytest.raises(pydantic.ValidationError):
        OpenAIAudioSpeechUsage.from_original_usage_dict(usage_data)


def test_openai_audio_speech_usage__extra_unknown_keys_are_passed__fields_are_accepted():
    usage_data = {
        "character_count": 150,
        "total_tokens": 150,
        "prompt_tokens": 150,
        "completion_tokens": 0,
        "extra_integer": 99,
        "ignored_string": "ignored",
    }
    usage = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_data)
    assert usage.character_count == 150
    assert usage.extra_integer == 99

    flat_dict = usage.to_backend_compatible_flat_dict("original_usage")
    assert flat_dict == {
        "original_usage.character_count": 150,
        "original_usage.total_tokens": 150,
        "original_usage.prompt_tokens": 150,
        "original_usage.completion_tokens": 0,
        "original_usage.extra_integer": 99,
    }
