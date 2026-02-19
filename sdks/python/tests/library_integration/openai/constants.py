from ...testlib import ANY_BUT_NONE

MODEL_FOR_TESTS = "gpt-4o-mini"
VIDEO_MODEL_FOR_TESTS = "sora-2"
VIDEO_SIZE_FOR_TESTS = "720x1280"  # Lowest resolution for faster/cheaper tests
TTS_MODEL_FOR_TESTS = "tts-1"
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
