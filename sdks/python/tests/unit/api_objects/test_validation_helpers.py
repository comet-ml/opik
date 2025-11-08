import logging

from opik.api_objects import validation_helpers
from opik.types import LLMProvider


LOGGER = logging.getLogger(__name__)


def test_validate_and_parse_usage_preserves_video_duration_seconds_only_usage() -> None:
    result = validation_helpers.validate_and_parse_usage(
        {"video_duration_seconds": 4}, LOGGER, LLMProvider.GOOGLE_AI
    )

    assert result == {"video_duration_seconds": 4}


def test_validate_and_parse_usage_merges_passthrough_with_parsed_usage() -> None:
    usage = {
        "prompt_token_count": 10,
        "total_token_count": 12,
        "video_duration_seconds": 3,
    }

    result = validation_helpers.validate_and_parse_usage(
        usage, LOGGER, LLMProvider.GOOGLE_AI
    )

    assert result is not None
    assert result["video_duration_seconds"] == 3
    assert result["prompt_tokens"] == 10
    assert result["original_usage.prompt_token_count"] == 10
