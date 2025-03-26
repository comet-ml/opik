from typing import Any, Dict

from . import base_original_provider_usage


class UnknownUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        return super().to_backend_compatible_flat_dict(parent_key_prefix)

    @classmethod
    def from_original_usage_dict(cls, usage: Dict[str, Any]) -> "UnknownUsage":
        return cls(**usage)
