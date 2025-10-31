SpanModel
=========

.. currentmodule:: opik.message_processing.emulation.models

.. autoclass:: SpanModel
    :special-members: __init__

Description
-----------

The ``SpanModel`` class represents a span model used to describe specific points in a process, their metadata, and associated data. This class is used to store and manipulate structured data for events or spans, including metadata, time markers, associated input/output, tags, and additional properties.

It serves as a representative structure for recording and organizing event-specific information, often used in applications like logging, distributed tracing, or data processing pipelines. In the context of Opik, spans represent individual operations or function calls within a larger trace.

Attributes
----------

Required Attributes
~~~~~~~~~~~~~~~~~~~

.. attribute:: id
   :type: str
   :noindex:

   Unique identifier for the span.

.. attribute:: start_time
   :type: datetime.datetime
   :noindex:

   Start time of the span, marking when the operation began.

Optional Attributes
~~~~~~~~~~~~~~~~~~~

.. attribute:: name
   :type: Optional[str]
   :noindex:
   :value: None

   Name of the span, if provided. This typically describes the operation being performed.

.. attribute:: input
   :type: Optional[Dict[str, Any]]
   :noindex:
   :value: None

   Input data associated with the span, if any. This contains the parameters or data passed to the operation.

.. attribute:: output
   :type: Optional[Dict[str, Any]]
   :noindex:
   :value: None

   Output data associated with the span, if any. This contains the results or return values from the operation.

.. attribute:: tags
   :type: Optional[List[str]]
   :noindex:
   :value: None

   List of tags linked to the span. Tags are used for categorization and filtering.

.. attribute:: metadata
   :type: Optional[Dict[str, Any]]
   :noindex:
   :value: None

   Additional metadata for the span. This can contain any custom information about the operation.

.. attribute:: type
   :type: str
   :noindex:
   :value: "general"

   Type of the span, defaulting to "general". Common types include "llm", "general", "tool", etc.

.. attribute:: usage
   :type: Optional[Dict[str, Any]]
   :noindex:
   :value: None

   Usage-related information for the span, such as token counts, API usage statistics, etc.

.. attribute:: end_time
   :type: Optional[datetime.datetime]
   :noindex:
   :value: None

   End time of the span, if available. This marks when the operation completed.

.. attribute:: project_name
   :type: str
   :noindex:
   :value: OPIK_PROJECT_DEFAULT_NAME

   Name of the project the span is associated with, defaulting to a predefined project name.

.. attribute:: model
   :type: Optional[str]
   :noindex:
   :value: None

   Model identification used, if applicable. This is commonly used for LLM spans to track which model was used.

.. attribute:: provider
   :type: Optional[str]
   :noindex:
   :value: None

   Provider of the span or associated services, if any. Examples include "openai", "anthropic", etc.

.. attribute:: error_info
   :type: Optional[ErrorInfoDict]
   :noindex:
   :value: None

   Error information or diagnostics for the span, if applicable. Contains details about any errors that occurred.

.. attribute:: total_cost
   :type: Optional[float]
   :noindex:
   :value: None

   Total cost incurred associated with this span, if relevant. This is useful for tracking API costs.

.. attribute:: last_updated_at
   :type: Optional[datetime.datetime]
   :noindex:
   :value: None

   Timestamp of when the span was last updated, if available.

Collection Attributes
~~~~~~~~~~~~~~~~~~~~~

.. attribute:: spans
   :type: List[SpanModel]
   :noindex:

   List of nested spans related to this span. This creates a hierarchical structure where spans can contain child spans.

.. attribute:: feedback_scores
   :type: List[FeedbackScoreModel]
   :noindex:

   List of feedback scores associated with the span. These scores are used for evaluation and quality assessment.

Examples
--------

Creating a basic span:

.. code-block:: python

    import datetime
    from opik.message_processing.emulation.models import SpanModel

    # Create a simple span
    span = SpanModel(
        id="span_123",
        start_time=datetime.datetime.now(),
        name="llm_call",
        type="llm",
        input={"prompt": "What is the capital of France?"},
        output={"response": "Paris is the capital of France."},
        model="gpt-4",
        provider="openai"
    )

Creating a span with nested spans:

.. code-block:: python

    # Create a parent span with child spans
    parent_span = SpanModel(
        id="parent_123",
        start_time=datetime.datetime.now(),
        name="complex_operation"
    )

    child_span = SpanModel(
        id="child_456",
        start_time=datetime.datetime.now(),
        name="preprocessing_step"
    )

    parent_span.spans.append(child_span)

Adding feedback scores to a span:

.. code-block:: python

    from opik.message_processing.emulation.models import FeedbackScoreModel

    # Add evaluation scores to the span
    quality_score = FeedbackScoreModel(
        id="score_789",
        name="response_quality",
        value=0.92,
        reason="High quality response with accurate information"
    )

    span.feedback_scores.append(quality_score)

Usage in Task Span Evaluation
-----------------------------

``SpanModel`` objects are particularly important in task span evaluation, where custom metrics can analyze the span data:

.. code-block:: python

    from opik.evaluation.metrics import BaseMetric, score_result

    class CustomSpanMetric(BaseMetric):
        def score(self, task_span: SpanModel) -> score_result.ScoreResult:
            # Access span properties for evaluation
            input_data = task_span.input
            output_data = task_span.output

            # Perform custom evaluation logic
            score_value = self.evaluate_span_quality(input_data, output_data)

            return score_result.ScoreResult(
                value=score_value,
                name=self.name,
                reason=f"Evaluated span '{task_span.name}'"
            )

Common Use Cases
----------------

``SpanModel`` is commonly used for:

- **Function Tracking**: Recording individual function or method calls
- **LLM Operations**: Tracking language model API calls with usage and cost information
- **Pipeline Steps**: Representing steps in data processing pipelines
- **Evaluation**: Providing detailed execution data for custom evaluation metrics
- **Debugging**: Analyzing the structure and performance of complex operations

See Also
--------

- :class:`TraceModel` - The parent container that holds spans
- :class:`FeedbackScoreModel` - For attaching evaluation scores to spans
- :doc:`../evaluation/evaluate` - For information about evaluating spans with custom metrics
