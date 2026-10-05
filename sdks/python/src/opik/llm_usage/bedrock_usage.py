from typing import Dict, Any, Optional, Tuple
from . import base_original_provider_usage


class BedrockUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Bedrock calls token usage data. Updated 04.08.2025"""

    inputTokens: int
    """The number of input tokens which were used."""

    outputTokens: int
    """The number of output tokens which were used."""

    cacheReadInputTokens: Optional[int] = None
    """The number of input tokens which were read from the cache."""

    cacheWriteInputTokens: Optional[int] = None
    """The number of input tokens which were written to the cache."""

    def get_billable_tokens(self) -> Tuple[int, int]:
        """Return (prompt_tokens, completion_tokens) reflecting true billed cost.

        With prompt caching enabled `inputTokens` counts only the tokens that
        were not served from cache, so both cache counters have to be added back
        to get the real prompt size. Same accounting as
        `AnthropicUsage.get_billable_tokens`, which serves the same models.
        https://docs.aws.amazon.com/bedrock/latest/userguide/quotas-token-burndown.html
        """
        # total_input = inputTokens + cacheReadInputTokens + cacheWriteInputTokens
        return (
            self.inputTokens
            + (self.cacheReadInputTokens or 0)
            + (self.cacheWriteInputTokens or 0),
            self.outputTokens,
        )

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "BedrockUsage":
        return cls(**usage)
