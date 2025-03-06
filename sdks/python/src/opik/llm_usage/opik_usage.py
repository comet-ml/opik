import pydantic
from typing import Union, Dict, Any
from . import openai_usage, google_usage

ProviderUsage = Union[
    openai_usage.OpenAICompletionsUsage,
    google_usage.GoogleGeminiUsage,
]


class OpikUsage(pydantic.BaseModel):
    completion_tokens: int
    prompt_tokens: int
    total_tokens: int

    provider_usage: ProviderUsage

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
