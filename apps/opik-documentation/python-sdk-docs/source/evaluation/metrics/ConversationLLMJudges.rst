Conversation LLM Judges
=======================

.. currentmodule:: opik.evaluation.metrics

These evaluators wrap GEval-style LLM judges so you can score full conversations
without manually extracting turns. They expect transcripts in the same format used
by :class:`~opik.evaluation.metrics.ConversationThreadMetric` and typically rely on
an OpenAI- or Azure-compatible backend. Refer to the relevant Fern guides for API
keys, rate limits, and pricing considerations.

Core Conversation Judges
------------------------

.. autoclass:: GEvalConversationMetric
    :special-members: __init__
    :members: score

.. autoclass:: ConversationalCoherenceMetric
    :special-members: __init__
    :members: score

.. autoclass:: SessionCompletenessQuality
    :special-members: __init__
    :members: score

.. autoclass:: UserFrustrationMetric
    :special-members: __init__
    :members: score

Specialized Variants
--------------------

.. autoclass:: ConversationComplianceRiskMetric
    :special-members: __init__
    :members: score

.. autoclass:: ConversationDialogueHelpfulnessMetric
    :special-members: __init__
    :members: score

.. autoclass:: ConversationQARelevanceMetric
    :special-members: __init__
    :members: score

.. autoclass:: ConversationSummarizationCoherenceMetric
    :special-members: __init__
    :members: score

.. autoclass:: ConversationSummarizationConsistencyMetric
    :special-members: __init__
    :members: score

.. autoclass:: ConversationPromptUncertaintyMetric
    :special-members: __init__
    :members: score
