from typing import Any

from . import base_original_provider_usage


class UnknownUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: dict[str, Any]) -> "UnknownUsage":
        return cls(**usage)
