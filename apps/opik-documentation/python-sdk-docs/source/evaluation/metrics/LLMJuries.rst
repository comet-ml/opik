LLM Juries
==========

.. currentmodule:: opik.evaluation.metrics

Use :class:`LLMJuriesJudge` when you want to call multiple judges and aggregate the
results into a single decision. Provide a list of judge instances plus an
aggregation strategy (majority vote, max confidence, etc.).

.. autoclass:: LLMJuriesJudge
    :special-members: __init__
    :members: score
