from opik.llm_usage.openai_audio_speech_usage import (
    OpenAIAudioSpeechUsage,
    AudioSpeechResponseMetadata,
)
from opik.llm_usage.opik_usage import OpikUsage


class TestOpenAIAudioSpeechUsage:
    def test_from_original_usage_dict__minimal(self):
        usage_dict = {"input_characters": 42}

        result = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_dict)

        assert result.input_characters == 42

    def test_from_original_usage_dict__with_extra_fields(self):
        usage_dict = {
            "input_characters": 100,
            "extra_field": "extra_value",
        }

        result = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_dict)

        assert result.input_characters == 100
        # Extra fields should be preserved due to pydantic's extra="allow"
        assert hasattr(result, "extra_field")

    def test_to_backend_compatible_flat_dict(self):
        usage = OpenAIAudioSpeechUsage(input_characters=75)

        result = usage.to_backend_compatible_flat_dict("test_prefix")

        expected = {"test_prefix.input_characters": 75}
        assert result == expected

    def test_to_backend_compatible_flat_dict__with_extra_fields(self):
        usage_dict = {
            "input_characters": 50,
            "custom_int_field": 123,  # Only int values are preserved
        }
        usage = OpenAIAudioSpeechUsage.from_original_usage_dict(usage_dict)

        result = usage.to_backend_compatible_flat_dict("prefix")

        expected = {
            "prefix.input_characters": 50,
            "prefix.custom_int_field": 123,  # Only int values are kept
        }
        assert result == expected


class TestAudioSpeechResponseMetadata:
    def test_creation__minimal(self):
        metadata = AudioSpeechResponseMetadata()

        assert metadata.response_format is None
        assert metadata.speed is None
        assert metadata.voice is None
        assert metadata.content_length is None

    def test_creation__full(self):
        metadata = AudioSpeechResponseMetadata(
            response_format="mp3",
            speed=1.2,
            voice="alloy",
            content_length=2048,
        )

        assert metadata.response_format == "mp3"
        assert metadata.speed == 1.2
        assert metadata.voice == "alloy"
        assert metadata.content_length == 2048

    def test_creation__with_extra_fields(self):
        metadata = AudioSpeechResponseMetadata(
            response_format="wav",
            custom_field="custom_value",
        )

        assert metadata.response_format == "wav"
        # Extra fields should be allowed due to pydantic's extra="allow"
        assert hasattr(metadata, "custom_field")
        assert metadata.custom_field == "custom_value"


class TestOpikUsageFromOpenAIAudioSpeech:
    def test_from_openai_audio_speech_dict__basic(self):
        usage_dict = {"input_characters": 25}

        result = OpikUsage.from_openai_audio_speech_dict(usage_dict)

        # For TTS, characters map to prompt tokens, no completion tokens
        assert result.prompt_tokens == 25
        assert result.total_tokens == 25
        assert result.completion_tokens is None

        # Check provider usage
        assert isinstance(result.provider_usage, OpenAIAudioSpeechUsage)
        assert result.provider_usage.input_characters == 25

    def test_from_openai_audio_speech_dict__zero_characters(self):
        usage_dict = {"input_characters": 0}

        result = OpikUsage.from_openai_audio_speech_dict(usage_dict)

        assert result.prompt_tokens == 0
        assert result.total_tokens == 0
        assert result.completion_tokens is None

    def test_from_openai_audio_speech_dict__large_character_count(self):
        usage_dict = {"input_characters": 10000}

        result = OpikUsage.from_openai_audio_speech_dict(usage_dict)

        assert result.prompt_tokens == 10000
        assert result.total_tokens == 10000
        assert result.completion_tokens is None

    def test_to_backend_compatible_full_usage_dict(self):
        usage_dict = {"input_characters": 150}
        opik_usage = OpikUsage.from_openai_audio_speech_dict(usage_dict)

        result = opik_usage.to_backend_compatible_full_usage_dict()

        expected = {
            "prompt_tokens": 150,
            "total_tokens": 150,
            "original_usage.input_characters": 150,
        }
        assert result == expected

    def test_to_backend_compatible_full_usage_dict__with_extra_fields(self):
        usage_dict = {
            "input_characters": 200,
            "extra_int_field": 123,  # Only int values are preserved
        }
        opik_usage = OpikUsage.from_openai_audio_speech_dict(usage_dict)

        result = opik_usage.to_backend_compatible_full_usage_dict()

        expected = {
            "prompt_tokens": 200,
            "total_tokens": 200,
            "original_usage.input_characters": 200,
            "original_usage.extra_int_field": 123,
        }
        assert result == expected

    def test_from_openai_audio_speech_dict__realistic_text_lengths(self):
        """Test with realistic text lengths for TTS."""
        test_cases = [
            ("Hello", 5),
            ("Hello, world!", 13),
            (
                "This is a longer sentence that someone might want to convert to speech.",
                71,
            ),
            ("Multi-line\ntext\nwith\nbreaks", 27),
        ]

        for text, expected_chars in test_cases:
            usage_dict = {"input_characters": len(text)}

            result = OpikUsage.from_openai_audio_speech_dict(usage_dict)

            assert result.prompt_tokens == expected_chars
            assert result.total_tokens == expected_chars
            assert result.completion_tokens is None
            assert result.provider_usage.input_characters == expected_chars
