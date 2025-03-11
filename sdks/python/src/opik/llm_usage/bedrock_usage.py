from typing import Dict, Any
from . import base_original_provider_usage


class BedrockUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Anthropic calls token usage data. Updated 11.03.2025"""

    inputTokens: int
    """The number of input tokens which were used."""

    outputTokens: int
    """The number of output tokens which were used."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "BedrockUsage":
        return cls(**usage)
