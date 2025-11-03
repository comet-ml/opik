FeedbackScoreModel
==================

.. currentmodule:: opik.message_processing.emulation.models

.. autoclass:: FeedbackScoreModel
    :special-members: __init__

Description
-----------

The ``FeedbackScoreModel`` class represents a feedback score used to evaluate specific spans or traces in the Opik system. It stores and manages feedback scores linked to defined criteria, including identifiers, names, values, categories, and explanations for each score.

This model is typically used in evaluation contexts where you need to score or rate the performance of traces and spans based on various metrics.

Attributes
----------

.. attribute:: id
   :type: str
   :noindex:

   Unique identifier for the feedback score.

.. attribute:: name
   :type: str
   :noindex:

   Name associated with the feedback score, typically describing the metric being measured.

.. attribute:: value
   :type: float
   :noindex:

   The numerical value of the feedback score. This represents the actual score or rating assigned.

.. attribute:: category_name
   :type: Optional[str]
   :value: None
   :noindex:

   Category to which the feedback score belongs, if any. This can be used to group related feedback scores together.

.. attribute:: reason
   :type: Optional[str]
   :value: None
   :noindex:

   Reason or explanation for the feedback score, if available. This provides context for why a particular score was assigned.

Examples
--------

Creating a basic feedback score:

.. code-block:: python

    from opik.message_processing.emulation.models import FeedbackScoreModel

    # Create a feedback score for a quality metric
    feedback_score = FeedbackScoreModel(
        id="score_123",
        name="response_quality",
        value=0.85,
        category_name="quality",
        reason="Response was accurate and well-structured"
    )

Creating a feedback score with minimal information:

.. code-block:: python

    # Create a simple feedback score
    simple_score = FeedbackScoreModel(
        id="score_456",
        name="accuracy",
        value=1.0
    )

Usage in Evaluation
-------------------

``FeedbackScoreModel`` objects are commonly used in:

- **Evaluation Metrics**: Storing results from custom evaluation metrics
- **Span Scoring**: Associating quality scores with specific spans
- **Trace Evaluation**: Rating overall trace performance
- **A/B Testing**: Comparing different model outputs with scored feedback

See Also
--------

- :class:`SpanModel` - Contains lists of feedback scores
- :class:`TraceModel` - Also contains lists of feedback scores
- :doc:`../evaluation/evaluate` - For information about evaluation metrics that generate these scores
