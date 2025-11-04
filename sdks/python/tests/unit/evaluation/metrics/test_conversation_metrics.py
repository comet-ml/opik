from opik.evaluation.metrics.conversation.heuristics.degeneration.metric import (
    ConversationDegenerationMetric,
)


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
