from ...testlib import (
    ANY_BUT_NONE,
)

BEDROCK_MODEL_FOR_TESTS = "us.anthropic.claude-sonnet-4-20250514-v1:0"

EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.inputTokens": ANY_BUT_NONE,
    "original_usage.outputTokens": ANY_BUT_NONE,
    "original_usage.totalTokens": ANY_BUT_NONE,
    # "original_usage.cacheReadInputTokens": ANY_BUT_NONE,
    # "original_usage.cacheWriteInputTokens": ANY_BUT_NONE,
}
