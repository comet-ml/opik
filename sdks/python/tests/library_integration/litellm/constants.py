from ...testlib import ANY_BUT_NONE, ANY_DICT


MODEL_FOR_TESTS = "gpt-4o-mini"

# Models for parametrized tests - used in streaming and non-streaming tests
# Only includes models that pass strict usage validation
TEST_MODELS_PARAMETRIZE = [
    ("gpt-4o-mini", "openai"),  # OpenAI - fully supported with usage tracking
    (
        "anthropic/claude-3-5-haiku-20241022",
        "anthropic",
    ),  # Anthropic - fully supported with usage tracking
    (
        "openai/gpt-4o-2024-08-06",
        "openai",
    ),  # OpenAI - fully supported with usage tracking
]

EXPECTED_LITELLM_USAGE_LOGGED_FORMAT = ANY_DICT.containing(
    {
        "prompt_tokens": ANY_BUT_NONE,
        "completion_tokens": ANY_BUT_NONE,
        "total_tokens": ANY_BUT_NONE,
    }
)
