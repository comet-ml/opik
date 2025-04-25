from ...testlib import ANY_BUT_NONE

MODEL_FOR_TESTS = "gpt-4o-mini"
EXPECTED_OPENAI_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.input_tokens": ANY_BUT_NONE,
    "original_usage.output_tokens": ANY_BUT_NONE,
    "original_usage.total_tokens": ANY_BUT_NONE,
    "original_usage.input_tokens_details.cached_tokens": ANY_BUT_NONE,
    "original_usage.output_tokens_details.reasoning_tokens": ANY_BUT_NONE,
}
