from ...testlib import ANY, ANY_DICT


EXPECTED_USAGE_GOOGLE = ANY_DICT.containing(
    {
        "completion_tokens": ANY,
        "prompt_tokens": ANY,
        "total_tokens": ANY,
        "original_usage.total_token_count": ANY,
        "original_usage.prompt_token_count": ANY,
    }
)
