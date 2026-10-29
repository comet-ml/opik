from ... import llm_constants
from ...testlib import ANY_BUT_NONE

MODEL_FOR_TESTS = llm_constants.OPENAI_GPT_NANO
VIDEO_MODEL_FOR_TESTS = llm_constants.OPENAI_SORA
VIDEO_SIZE_FOR_TESTS = "720x1280"  # Lowest resolution for faster/cheaper tests
DECISIONS_MODEL_FOR_TESTS = llm_constants.OPENAI_DECISIONS
DECISIONS_INPUT_FOR_TESTS = (
    "Customer: I was charged twice for my subscription this month. Please fix it!"
)
DECISIONS_QUESTIONS_FOR_TESTS = [
    {
        "name": "is_complaint",
        "type": "predicate",
        "instructions": "Is the customer complaining?",
    },
    {
        "name": "topic",
        "type": "choice",
        "instructions": "What is the message about?",
        "choices": [{"value": "billing"}, {"value": "shipping"}, {"value": "other"}],
    },
    {
        "name": "frustration",
        "type": "score",
        "instructions": "How frustrated is the customer?",
        "levels": [{"label": "calm"}, {"label": "annoyed"}, {"label": "furious"}],
    },
]
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
