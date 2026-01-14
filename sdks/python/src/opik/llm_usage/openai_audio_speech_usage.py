from typing import Dict, Any
from . import base_original_provider_usage


class OpenAIAudioSpeechUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """OpenAI Audio Speech (TTS) API usage data."""

    character_count: int
    """The number of characters in the input text."""

    total_tokens: int
    """Total tokens (stores character count for TTS compatibility)."""

    prompt_tokens: int
    """Prompt tokens (stores character count for TTS compatibility)."""

    completion_tokens: int
    """Completion tokens (always 0 for TTS)."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        result = {**self.__dict__}

        return self.flatten_result_and_add_model_extra(
            result=result, parent_key_prefix=parent_key_prefix
        )

    @classmethod
    def from_original_usage_dict(
        cls, usage_dict: Dict[str, Any]
    ) -> "OpenAIAudioSpeechUsage":
        usage_dict = {**usage_dict}

        if "character_count" not in usage_dict:
            raise KeyError("character_count is required for TTS usage")

        character_count = usage_dict.pop("character_count")
        total_tokens = usage_dict.pop("total_tokens", character_count)
        prompt_tokens = usage_dict.pop("prompt_tokens", character_count)
        completion_tokens = usage_dict.pop("completion_tokens", 0)

        return cls(
            character_count=character_count,
            total_tokens=total_tokens,
            prompt_tokens=prompt_tokens,
            completion_tokens=completion_tokens,
            **usage_dict,
        )
