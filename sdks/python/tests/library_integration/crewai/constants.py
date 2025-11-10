from ...testlib import ANY_BUT_NONE

PROJECT_NAME = "crewai-test"

MODEL_NAME_SHORT = "gpt-4o-mini"

EXPECTED_SHORT_OPENAI_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    # original usage is not asserted
}
