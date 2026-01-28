from typing import Any


from . import base_original_provider_usage


class GoogleGeminiUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Google AI / VertexAI calls token usage data. Updated 11.03.2025"""

    candidates_token_count: int | None
    """Number of tokens in the response(s)."""

    prompt_token_count: int
    """Number of tokens in the request. When `cached_content` is set, this is still the total effective prompt size meaning this includes the number of tokens in the cached content."""

    total_token_count: int
    """Total token count for prompt and response candidates."""

    cached_content_token_count: int | None = None
    """Output only. Number of tokens in the cached part in the input (the cached content)."""

    thoughts_token_count: int | None = None
    """Number of tokens spent for reasoning. Only available for Gemini models with reasoning enabled. (Gemini-2.5 and above)"""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: dict[str, Any]) -> "GoogleGeminiUsage":
        return cls(**usage)
