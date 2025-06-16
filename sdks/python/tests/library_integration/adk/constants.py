import uuid

APP_NAME = "ADK_app"
USER_ID = "ADK_test_user"
SESSION_ID = "ADK_" + str(uuid.uuid4())
MODEL_NAME = "gemini-2.0-flash"

EXPECTED_USAGE_KEYS_GOOGLE = [
    "completion_tokens",
    "original_usage.candidates_token_count",
    "original_usage.prompt_token_count",
    "original_usage.total_token_count",
    "prompt_tokens",
    "total_tokens",
]
