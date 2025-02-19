from typing import Dict, Any
from opik.types import UsageDictGoogle


def opik_usage_from_google_format(provider_usage: Dict[str, Any]) -> UsageDictGoogle:
    return UsageDictGoogle(
        completion_tokens=provider_usage["candidates_token_count"],
        prompt_tokens=provider_usage["prompt_token_count"],
        total_tokens=provider_usage["total_token_count"],
        **provider_usage,  # type: ignore
    )
