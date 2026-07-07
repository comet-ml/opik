from typing import Optional, Dict, Any

import pydantic

from . import base_original_provider_usage


class MistralPromptTokensDetails(pydantic.BaseModel):
    model_config = pydantic.ConfigDict(extra="allow")

    audio_tokens: Optional[int] = None
    """Audio input tokens present in the prompt."""

    cached_tokens: Optional[int] = None
    """Cached tokens present in the prompt."""


class MistralUsage(base_original_provider_usage.BaseOriginalProviderUsage):
    """Mistral AI (`mistralai`) token usage data (`UsageInfo`)."""

    completion_tokens: int
    """Number of tokens in the generated completion."""

    prompt_tokens: int
    """Number of tokens in the prompt."""

    total_tokens: int
    """Total number of tokens used in the request (prompt + completion)."""

    prompt_tokens_details: Optional[MistralPromptTokensDetails] = None
    """Breakdown of tokens used in the prompt."""

    def to_backend_compatible_flat_dict(self, parent_key_prefix: str) -> Dict[str, int]:
        result = {**self.__dict__}

        if self.prompt_tokens_details is not None:
            result["prompt_tokens_details"] = self.prompt_tokens_details.model_dump()

        return self.flatten_result_and_add_model_extra(
            result=result, parent_key_prefix=parent_key_prefix
        )

    @classmethod
    def from_original_usage_dict(cls, usage_dict: Dict[str, Any]) -> "MistralUsage":
        usage_dict = {**usage_dict}
        prompt_tokens_details_raw = usage_dict.pop("prompt_tokens_details", None)

        prompt_tokens_details = (
            MistralPromptTokensDetails(**prompt_tokens_details_raw)
            if isinstance(prompt_tokens_details_raw, dict)
            else None
        )

        return cls(
            **usage_dict,
            prompt_tokens_details=prompt_tokens_details,
        )
