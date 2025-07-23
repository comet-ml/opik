from typing import Dict, Any

from ...testlib import assert_dict_has_keys


def assert_usage_validity(usage: Dict[str, Any]):
    required_usage_keys = [
        "completion_tokens",
        "prompt_tokens",
        "total_tokens",
        "original_usage.total_token_count",
        "original_usage.candidates_token_count",
        "original_usage.prompt_token_count",
    ]

    assert_dict_has_keys(usage, required_usage_keys)
