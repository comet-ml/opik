Conversation Heuristic Metrics
==============================

.. currentmodule:: opik.evaluation.metrics

Use these fast, rule-based metrics when you want lightweight signals about dialogue
quality without invoking an LLM judge. They operate over full conversation
transcripts and surface issues such as repetition and missing context.

.. autoclass:: ConversationDegenerationMetric
    :special-members: __init__
    :members: score

.. autoclass:: KnowledgeRetentionMetric
    :special-members: __init__
    :members: score
