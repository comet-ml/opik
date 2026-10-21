import asyncio

import pytest

from opik.evaluation.metrics.conversation.heuristics.degeneration.metric import (
    ConversationDegenerationMetric,
)
from opik.evaluation.metrics.conversation.heuristics.knowledge_retention.metric import (
    KnowledgeRetentionMetric,
)
from opik.evaluation.metrics.score_result import ScoreResult


def test_conversation_degeneration_detects_repetition():
    conversation = [
        {"role": "user", "content": "Hi"},
        {"role": "assistant", "content": "Hello, how can I help you today?"},
        {"role": "user", "content": "I need assistance"},
        {
            "role": "assistant",
            "content": "I'm sorry, I'm sorry, I'm sorry, I cannot assist with that request.",
        },
        {
            "role": "assistant",
            "content": "I'm sorry, I'm sorry, I'm sorry, I cannot assist with that request.",
        },
    ]

    metric = ConversationDegenerationMetric(track=False)
    result = metric.score(conversation=conversation)

    assert result.value > 0.5
    assert result.metadata is not None
    assert len(result.metadata["per_turn"]) == 3  # assistant turns with tokens


def test_conversation_degeneration_low_repetition():
    conversation = [
        {"role": "assistant", "content": "Hello, thanks for your question."},
        {
            "role": "assistant",
            "content": "I looked into your account and confirmed the balance is $150.",
        },
        {
            "role": "assistant",
            "content": "Let me know if you'd like a breakdown of recent transactions.",
        },
    ]

    metric = ConversationDegenerationMetric(track=False)
    result = metric.score(conversation=conversation)

    assert 0.0 <= result.value < 0.3


@pytest.mark.parametrize("wordless_reply", ["...", "???", "!!!", "   "])
def test_conversation_degeneration_scores_wordless_turn(wordless_reply):
    conversation = [
        {"role": "user", "content": "Hi"},
        {"role": "assistant", "content": "Hello, thanks for your question."},
        {"role": "user", "content": "Can you help?"},
        {"role": "assistant", "content": wordless_reply},
    ]

    metric = ConversationDegenerationMetric(track=False)
    result = metric.score(conversation=conversation)

    per_turn = result.metadata["per_turn"]
    assert len(per_turn) == 2
    assert [turn["is_wordless"] for turn in per_turn] == [0.0, 1.0]
    # Scored through the same four factors as a one-word reply, not saturated.
    one_word = metric.score(conversation=[{"role": "assistant", "content": "ok"}])
    assert per_turn[1]["degeneration_score"] == one_word.value
    assert result.value == one_word.value < 1.0


def test_conversation_degeneration_wordless_turn_does_not_hide_a_loop():
    looping_reply = "Let me check the balance of your savings account for you."
    conversation = [
        {"role": "assistant", "content": looping_reply},
        {"role": "assistant", "content": "..."},
        {"role": "assistant", "content": looping_reply},
    ]

    metric = ConversationDegenerationMetric(track=False)
    result = metric.score(conversation=conversation)

    per_turn = result.metadata["per_turn"]
    assert per_turn[1]["overlap_previous"] == 0.0
    assert per_turn[2]["overlap_previous"] == 1.0

    without_filler = metric.score(
        conversation=[conversation[0], conversation[2]],
    )
    assert result.value == without_filler.value


def test_conversation_degeneration_all_wordless_turns():
    conversation = [
        {"role": "assistant", "content": "..."},
        {"role": "assistant", "content": "???"},
    ]

    metric = ConversationDegenerationMetric(track=False)
    result = metric.score(conversation=conversation)

    wordless_turn = {
        "repetition_ratio": 0.0,
        "overlap_previous": 0.0,
        "fallback_hit": 0.0,
        "normalized_entropy": 1.0,
        "is_wordless": 1.0,
        "degeneration_score": 0.25,
    }
    assert result.metadata["per_turn"] == [wordless_turn, wordless_turn]
    assert result.value == 0.25
    assert result.metadata["average_score"] == 0.25


@pytest.mark.parametrize(
    "metric",
    [
        KnowledgeRetentionMetric(track=False),
        ConversationDegenerationMetric(track=False),
    ],
    ids=["KnowledgeRetentionMetric", "ConversationDegenerationMetric"],
)
def test_conversation_thread_metric_ascore_delegates_to_score(metric):
    """ascore() returns the same result as score() for the given
    conversation.
    """
    conversation = [
        {"role": "user", "content": "Hi"},
        {"role": "assistant", "content": "Hello, how can I help you today?"},
        {"role": "user", "content": "I need assistance"},
        {
            "role": "assistant",
            "content": "I'm sorry, I'm sorry, I'm sorry, I cannot assist with that request.",
        },
    ]

    sync_result = metric.score(conversation=conversation)
    async_result = asyncio.run(metric.ascore(conversation=conversation))

    assert isinstance(async_result, ScoreResult)
    assert async_result == sync_result
