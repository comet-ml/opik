import os

import certifi
import pytest

from opik.evaluation.metrics.conversation.llm_judges.user_frustration import (
    metric as user_frustration,
)
from ...testlib import assert_helpers


@pytest.fixture
def real_model_conversation():
    return [
        {"role": "user", "content": "How do I center a div using CSS?"},
        {
            "role": "assistant",
            "content": "There are many ways to center elements in CSS.",
        },
        {"role": "user", "content": "Okay... can you show me one?"},
        {
            "role": "assistant",
            "content": "Sure. It depends on the context — are you centering horizontally, vertically, or both?",
        },
        {"role": "user", "content": "Both. Just give me a basic example."},
        {
            "role": "assistant",
            "content": "Alright. You can use flexbox, grid, or margin auto. All of them work well.",
        },
        {"role": "user", "content": "Could you please just write the code?"},
        {
            "role": "assistant",
            "content": "Here’s one way:\n\n```css\ndiv {\n  display: flex;\n}\n```\nThat sets it up for centering.",
        },
        {
            "role": "user",
            "content": "But this doesn’t even center anything! This is incomplete.",
        },
        {
            "role": "assistant",
            "content": "You're right. You also need `justify-content` and `align-items`.",
        },
        {
            "role": "user",
            "content": "Why didn’t you include those in the first place? This is wasting my time.",
        },
    ]


def test_user_frustration_metric(real_model_conversation):
    """Integration test with a real model."""
    metric = user_frustration.UserFrustrationMetric(
        track=False, window_size=3
    )  # Uses default model
    result = metric.score(real_model_conversation)

    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary
    assert result.name == "user_frustration_score"
    assert result.value is not None


@pytest.mark.asyncio
async def test_user_frustration_metric_async(real_model_conversation):
    """Integration test with a real model asyncio mode."""
    os.environ["SSL_CERT_FILE"] = certifi.where()

    metric = user_frustration.UserFrustrationMetric(track=False, window_size=3)
    result = await metric.ascore(real_model_conversation)

    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary
    assert result.name == "user_frustration_score"
