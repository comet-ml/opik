from ... import llm_constants
from ...testlib import ANY_BUT_NONE, ANY_DICT


MODEL_FOR_TESTS = llm_constants.OPENAI_GPT_NANO

# Models for parametrized tests - used in streaming and non-streaming tests.
# Each row carries any provider-specific extra kwargs the call needs; OpenAI
# reasoning models get `reasoning_effort=minimal` so they don't spend the
# whole `max_tokens` budget on internal reasoning before emitting content.
_OPENAI_REASONING = {"reasoning_effort": llm_constants.OPENAI_REASONING_EFFORT}

TEST_MODELS_PARAMETRIZE = [
    (llm_constants.OPENAI_GPT_NANO, "openai", _OPENAI_REASONING),
    (llm_constants.LITELLM_ANTHROPIC_CLAUDE_HAIKU, "anthropic", {}),
    (llm_constants.LITELLM_OPENAI_GPT_NANO, "openai", _OPENAI_REASONING),
]

EXPECTED_LITELLM_USAGE_LOGGED_FORMAT = ANY_DICT.containing(
    {
        "prompt_tokens": ANY_BUT_NONE,
        "completion_tokens": ANY_BUT_NONE,
        "total_tokens": ANY_BUT_NONE,
    }
)
