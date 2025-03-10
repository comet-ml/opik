import pydantic
from typing import Union, Dict, Any
from . import openai_usage, google_usage, anthropic_usage

ProviderUsage = Union[
    openai_usage.OpenAICompletionsUsage,
    google_usage.GoogleGeminiUsage,
    anthropic_usage.AnthropicUsage,
]


class OpikUsage(pydantic.BaseModel):
    completion_tokens: int
    prompt_tokens: int
    total_tokens: int

    provider_usage: ProviderUsage

    def to_backend_compatible_full_usage_dict(self) -> Dict[str, int]:
        short_usage = {
            "completion_tokens": self.completion_tokens,
            "prompt_tokens": self.prompt_tokens,
            "total_tokens": self.total_tokens,
        }

        provider_usage: Dict[str, int] = (
            self.provider_usage.to_backend_compatible_flat_dict(
                parent_key_prefix="original_usage"
            )
        )

        return {**short_usage, **provider_usage}

    @classmethod
    def from_openai_completions_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = openai_usage.OpenAICompletionsUsage.from_original_usage_dict(
            usage
        )

        return cls(
            completion_tokens=provider_usage.completion_tokens,
            prompt_tokens=provider_usage.prompt_tokens,
            total_tokens=provider_usage.total_tokens,
            provider_usage=provider_usage,
        )

    @classmethod
    def from_google_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = google_usage.GoogleGeminiUsage.from_original_usage_dict(usage)

        return cls(
            completion_tokens=provider_usage.candidates_token_count,
            prompt_tokens=provider_usage.prompt_token_count,
            total_tokens=provider_usage.total_token_count,
            provider_usage=provider_usage,
        )

    @classmethod
    def from_anthropic_dict(cls, usage: Dict[str, Any]) -> "OpikUsage":
        provider_usage = anthropic_usage.AnthropicUsage.from_original_usage_dict(usage)

        prompt_tokens = provider_usage.input_tokens + (
            provider_usage.cache_read_input_tokens
            if provider_usage.cache_read_input_tokens is not None
            else 0
        )
        completion_tokens = provider_usage.output_tokens + (
            provider_usage.cache_creation_input_tokens
            if provider_usage.cache_creation_input_tokens is not None
            else 0
        )
        total_tokens = prompt_tokens + completion_tokens

        return cls(
            completion_tokens=completion_tokens,
            prompt_tokens=prompt_tokens,
            total_tokens=total_tokens,
            provider_usage=provider_usage,
        )
