from typing import Optional, Dict, Any
from . import base_original_provider_usage


class AnthropicUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Anthropic calls token usage data. Updated 11.03.2025"""

    input_tokens: int
    """The number of input tokens which were used."""

    output_tokens: int
    """The number of output tokens which were used."""

    cache_creation_input_tokens: Optional[int] = None
    """The number of input tokens used to create the cache entry."""

    cache_read_input_tokens: Optional[int] = None
    """The number of input tokens read from the cache."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "AnthropicUsage":
        return cls(**usage)
