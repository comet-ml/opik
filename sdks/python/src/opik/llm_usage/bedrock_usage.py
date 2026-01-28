from typing import Any
from . import base_original_provider_usage


class BedrockUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Bedrock calls token usage data. Updated 04.08.2025"""

    inputTokens: int
    """The number of input tokens which were used."""

    outputTokens: int
    """The number of output tokens which were used."""

    cacheReadInputTokens: int | None = None
    """The number of input tokens which were read from the cache."""

    cacheWriteInputTokens: int | None = None
    """The number of input tokens which were written to the cache."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: dict[str, Any]) -> "BedrockUsage":
        return cls(**usage)
