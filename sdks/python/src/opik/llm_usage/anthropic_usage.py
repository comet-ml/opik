from typing import Any, Dict, List, Optional, Tuple
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

    iterations: Optional[List[Dict[str, Any]]] = None
    """Per-iteration token usage breakdown, populated when compaction fires."""

    def get_billable_tokens(self) -> Tuple[int, int]:
        """Return (prompt_tokens, completion_tokens) reflecting true billed cost.

        When compaction fires, top-level input_tokens/output_tokens exclude the
        compaction iteration. Summing all iterations gives the correct total.
        https://platform.claude.com/docs/en/build-with-claude/compaction#understanding-usage
        """
        if self.iterations:
            prompt = sum(
                (it.get("input_tokens", 0) or 0)
                + (it.get("cache_read_input_tokens", 0) or 0)
                + (it.get("cache_creation_input_tokens", 0) or 0)
                for it in self.iterations
            )
            completion = sum(it.get("output_tokens", 0) or 0 for it in self.iterations)
            return prompt, completion

        # total_input = input_tokens + cache_read_input_tokens + cache_creation_input_tokens
        # https://platform.claude.com/docs/en/build-with-claude/prompt-caching#tracking-cache-performance
        return (
            self.input_tokens
            + (self.cache_read_input_tokens or 0)
            + (self.cache_creation_input_tokens or 0),
            self.output_tokens,
        )

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "AnthropicUsage":
        return cls(**usage)
