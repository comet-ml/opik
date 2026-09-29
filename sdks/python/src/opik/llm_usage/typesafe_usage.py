from typing import Any, Dict, Optional

from . import base_original_provider_usage


class TypeSafeUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """TypeSafe AI (`typesafe-sdk`) token usage data (`Usage`) reported by Jev models."""

    input_tokens: Optional[int] = None
    """Number of billable input tokens, or None when the API did not report it."""

    output_tokens: Optional[int] = None
    """Number of output tokens (currently free of charge), or None when the API did not report it."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "TypeSafeUsage":
        return cls(**usage)
