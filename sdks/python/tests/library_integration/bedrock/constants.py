from ... import llm_constants
from ...testlib import (
    ANY_BUT_NONE,
)

BEDROCK_MODEL_FOR_TESTS = llm_constants.BEDROCK_CLAUDE_SONNET
MISTRAL_PIXTRAL_MODEL_FOR_TESTS = llm_constants.BEDROCK_MISTRAL_PIXTRAL
MISTRAL_PIXTRAL_REGION_FOR_TESTS = llm_constants.BEDROCK_MISTRAL_PIXTRAL_REGION

EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.inputTokens": ANY_BUT_NONE,
    "original_usage.outputTokens": ANY_BUT_NONE,
    "original_usage.totalTokens": ANY_BUT_NONE,
    # Cache token counts are deliberately not asserted here: ANY_BUT_NONE matches
    # 0, so it would pass whether or not they are reported. They are checked
    # numerically in the prompt caching test instead.
}
