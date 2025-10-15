from ...testlib import ANY_BUT_NONE, ANY_DICT


MODEL_FOR_TESTS = "gpt-4o-mini"
EXPECTED_LITELLM_USAGE_LOGGED_FORMAT = ANY_DICT.containing({
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
})
