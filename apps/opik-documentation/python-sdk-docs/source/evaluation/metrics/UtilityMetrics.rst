Utility Metrics
===============

.. currentmodule:: opik.evaluation.metrics

Helper components that adapt or combine other metrics. Use them to stitch existing
evaluators together without rewriting orchestration logic.

.. autoclass:: AggregatedMetric
    :special-members: __init__
    :members: score, validate_score_arguments

.. autoclass:: RagasMetricWrapper
    :special-members: __init__
    :members: score
