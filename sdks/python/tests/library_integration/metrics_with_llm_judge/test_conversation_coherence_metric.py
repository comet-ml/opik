import os

import certifi
import pytest

from opik.evaluation.metrics.conversation.llm_judges.conversational_coherence import (
    metric as conversational_coherence,
)
from ...testlib import assert_helpers


@pytest.fixture
def real_model_conversation():
    return [
        {
            "role": "user",
            "content": "I need to book a flight to New York and find a hotel.",
        },
        {
            "role": "assistant",
            "content": "I can help you with that. For flights to New York, what dates are you looking to travel?",
        },
        {"role": "user", "content": "Next weekend, from Friday to Sunday."},
        {
            "role": "assistant",
            "content": "Great! I recommend checking airlines like Delta, United, or JetBlue for flights to New York next weekend. For hotels, what's your budget range and preferred location in New York?",
        },
        {"role": "user", "content": "Around $200 per night, preferably in Manhattan."},
        {
            "role": "assistant",
            "content": "For Manhattan hotels around $200/night, you might want to look at options like Hotel Beacon, Pod 51, or CitizenM Times Square. These are well-rated options in that price range. Would you like more specific recommendations for any of these?",
        },
    ]


def test_conversation_coherence_metric(real_model_conversation):
    """Integration test with a real model."""
    metric = conversational_coherence.ConversationalCoherenceMetric(
        track=False, window_size=2
    )  # Uses default model
    result = metric.score(real_model_conversation)

    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary


@pytest.mark.asyncio
async def test_conversation_coherence_metric_async(real_model_conversation):
    """Integration test with a real model asyncio mode."""
    os.environ["SSL_CERT_FILE"] = certifi.where()

    metric = conversational_coherence.ConversationalCoherenceMetric(
        track=False, window_size=2
    )  # Uses default model
    result = await metric.ascore(real_model_conversation)

    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary
