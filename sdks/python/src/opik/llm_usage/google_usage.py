from typing import Optional, Dict, Any
from . import base_original_provider_usage


class GoogleGeminiUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Google AI / VertexAI calls token usage data. Updated 11.03.2025"""

    candidates_token_count: int
    """Number of tokens in the response(s)."""

    prompt_token_count: int
    """Number of tokens in the request. When `cached_content` is set, this is still the total effective prompt size meaning this includes the number of tokens in the cached content."""

    total_token_count: int
    """Total token count for prompt and response candidates."""

    cached_content_token_count: Optional[int] = None
    """Output only. Number of tokens in the cached part in the input (the cached content)."""

    thoughts_token_count: Optional[int] = None
    """Number of tokens spent for reasoning. Only available for Gemini models with reasoning enabled. (Gemini-2.5 and above)"""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "GoogleGeminiUsage":
        return cls(**usage)
