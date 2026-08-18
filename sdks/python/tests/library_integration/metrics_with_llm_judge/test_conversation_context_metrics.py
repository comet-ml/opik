import pytest

from opik.evaluation.metrics.conversation.llm_judges.conversational_coherence import (
    metric as conversational_coherence,
)
from ...testlib import assert_helpers


@pytest.fixture
def grounded_conversation():
    return [
        {"role": "user", "content": "What is the fee for going overdrawn?"},
        {
            "role": "assistant",
            "content": "Going overdrawn costs 5% of the overdrawn amount.",
            "context": [
                "The overdraft fee is 5% of the overdrawn amount, charged monthly."
            ],
        },
        {"role": "user", "content": "And how long do I have to pay it back?"},
        {
            "role": "assistant",
            "content": "You have 30 days to repay an overdraft.",
            "context": ["Customers must repay an overdraft within 30 days."],
        },
    ]


@pytest.fixture
def ungrounded_conversation():
    return [
        {"role": "user", "content": "What is the fee for going overdrawn?"},
        {
            "role": "assistant",
            "content": "Overdrafts are completely free and you can repay them whenever you like.",
            "context": [
                "The overdraft fee is 5% of the overdrawn amount, charged monthly.",
                "Customers must repay an overdraft within 30 days.",
            ],
        },
    ]


def test_conversational_coherence__with_documents__grounded_answers(
    grounded_conversation,
):
    """Integration test with a real model."""
    metric = conversational_coherence.ConversationalCoherenceMetric(
        track=False, reasoning_effort="minimal"
    )
    result = metric.score(grounded_conversation)

    assert not isinstance(result, list)
    assert result.name == "conversational_coherence_score"
    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary


def test_conversational_coherence__with_documents__contradicting_answer(
    ungrounded_conversation,
):
    """Integration test with a real model."""
    metric = conversational_coherence.ConversationalCoherenceMetric(
        track=False, reasoning_effort="minimal"
    )
    result = metric.score(ungrounded_conversation)

    assert not isinstance(result, list)
    assert_helpers.assert_score_result(result)
    # We don't assert specific values since the real model's output may vary


def test_conversational_coherence__without_documents__unchanged_behaviour(
    grounded_conversation,
):
    """Without documents the metric returns the same single score it always did."""
    plain = [
        {key: value for key, value in message.items() if key != "context"}
        for message in grounded_conversation
    ]
    metric = conversational_coherence.ConversationalCoherenceMetric(
        track=False, reasoning_effort="minimal"
    )
    result = metric.score(plain)

    assert not isinstance(result, list)
    assert result.name == "conversational_coherence_score"
    assert_helpers.assert_score_result(result)
