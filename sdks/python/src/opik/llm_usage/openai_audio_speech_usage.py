import pydantic
from typing import Optional, Dict, Any
from . import base_original_provider_usage


class OpenAIAudioSpeechUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """OpenAI TTS audio.speech endpoint usage data (character-based billing)."""

    input_characters: int
    """Number of characters in the input text that was synthesized to speech."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        result = {**self.__dict__}
        return self.flatten_result_and_add_model_extra(
            result=result, parent_key_prefix=parent_key_prefix
        )

    @classmethod
    def from_original_usage_dict(
        cls, usage_dict: Dict[str, Any]
    ) -> "OpenAIAudioSpeechUsage":
        return cls(**usage_dict)


class AudioSpeechResponseMetadata(pydantic.BaseModel):
    """Metadata about the audio speech response."""

    model_config = pydantic.ConfigDict(extra="allow")

    response_format: Optional[str] = None
    """Audio format of the response (mp3, opus, aac, flac, wav, pcm)."""

    speed: Optional[float] = None
    """Playback speed used for the synthesis."""

    voice: Optional[str] = None
    """Voice used for the synthesis."""

    content_length: Optional[int] = None
    """Size of the audio response in bytes."""
