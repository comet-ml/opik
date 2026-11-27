import os

import certifi
import pytest

from opik.evaluation.metrics.conversation.llm_judges.session_completeness import (
    metric as session_completeness,
)

from ...testlib import assert_helpers


@pytest.fixture
def real_model_conversation():
    # A single user goal keeps the metric at three calls: goal extraction,
    # one goal verdict, and the reason.
    return [
        {
            "role": "user",
            "content": "I need to book a flight to New York next Friday.",
        },
        {
            "role": "assistant",
            "content": "Done. You're booked on a Delta flight to New York departing next Friday at 9 AM.",
        },
    ]


def test__session_completeness_quality__with_real_model__happy_path(
    ensure_openai_configured, real_model_conversation
):
    """Integration test with a real model."""
    metric = session_completeness.SessionCompletenessQuality(
        track=False, reasoning_effort="minimal"
    )  # Uses default model
    result = metric.score(real_model_conversation)

    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary


@pytest.mark.asyncio
async def test__session_completeness_quality__with_real_model_async__happy_path(
    ensure_openai_configured, real_model_conversation
):
    os.environ["SSL_CERT_FILE"] = certifi.where()

    """Integration test with a real model asyncio mode."""
    metric = session_completeness.SessionCompletenessQuality(
        track=False, reasoning_effort="minimal"
    )  # Uses default model
    result = await metric.ascore(real_model_conversation)

    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary
