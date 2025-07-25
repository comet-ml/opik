from typing import Dict, Any

from . import base_original_provider_usage


class OpenAISpeechUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    character_count: int

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        result = {**self.__dict__}
        return self.flatten_result_and_add_model_extra(
            result=result, parent_key_prefix=parent_key_prefix
        )

    @classmethod
    def from_original_usage_dict(
        cls, usage_dict: Dict[str, Any]
    ) -> "OpenAISpeechUsage":
        return cls(**usage_dict)
