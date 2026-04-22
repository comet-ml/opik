from ... import llm_constants
from ...testlib import ANY_BUT_NONE, ANY_DICT


MODEL_FOR_TESTS = llm_constants.OPENAI_GPT_NANO

# Models for parametrized tests - used in streaming and non-streaming tests
# Only includes models that pass strict usage validation
TEST_MODELS_PARAMETRIZE = [
    (
        llm_constants.OPENAI_GPT_NANO,
        "openai",
    ),  # OpenAI - fully supported with usage tracking
    (
        llm_constants.LITELLM_ANTHROPIC_CLAUDE_HAIKU,
        "anthropic",
    ),  # Anthropic - fully supported with usage tracking
    (
        llm_constants.LITELLM_OPENAI_GPT_NANO,
        "openai",
    ),  # OpenAI - same model via LiteLLM provider/model form
]

EXPECTED_LITELLM_USAGE_LOGGED_FORMAT = ANY_DICT.containing(
    {
        "prompt_tokens": ANY_BUT_NONE,
        "completion_tokens": ANY_BUT_NONE,
        "total_tokens": ANY_BUT_NONE,
    }
)
