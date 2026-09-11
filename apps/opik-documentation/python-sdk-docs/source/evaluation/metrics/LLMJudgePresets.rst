LLM Judge Presets
=================

.. currentmodule:: opik.evaluation.metrics

These presets wrap common GEval prompt templates so you can instantiate judges with
one line of code. Provide an appropriate ``model`` (for example, ``"gpt-4o"``) and
ensure the backing provider is configured via ``opik.configure``.

Agent & Task Presets
--------------------

.. autoclass:: AgentTaskCompletionJudge
    :special-members: __init__
    :members: score

.. autoclass:: AgentToolCorrectnessJudge
    :special-members: __init__
    :members: score

Conversation Quality Presets
----------------------------

.. autoclass:: DialogueHelpfulnessJudge
    :special-members: __init__
    :members: score

.. autoclass:: QARelevanceJudge
    :special-members: __init__
    :members: score

.. autoclass:: SummarizationCoherenceJudge
    :special-members: __init__
    :members: score

.. autoclass:: SummarizationConsistencyJudge
    :special-members: __init__
    :members: score

.. autoclass:: PromptUncertaintyJudge
    :special-members: __init__
    :members: score

Risk & Bias Presets
-------------------

.. autoclass:: ComplianceRiskJudge
    :special-members: __init__
    :members: score

.. autoclass:: DemographicBiasJudge
    :special-members: __init__
    :members: score

.. autoclass:: GenderBiasJudge
    :special-members: __init__
    :members: score

.. autoclass:: PoliticalBiasJudge
    :special-members: __init__
    :members: score

.. autoclass:: RegionalBiasJudge
    :special-members: __init__
    :members: score

.. autoclass:: ReligiousBiasJudge
    :special-members: __init__
    :members: score
