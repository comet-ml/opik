Message Processing Emulation Models
====================================

.. currentmodule:: opik.message_processing.emulation.models

This module provides data models used for message processing emulation in Opik. These models represent the core data structures for traces, spans, and feedback scores that are used internally by the Opik SDK during evaluation.

Overview
--------

The message processing emulation models are primarily used in evaluation contexts, particularly for task span evaluation where custom metrics need access to detailed execution information. These models provide a structured representation of:

- **Traces**: Complete execution paths of requests or operations
- **Spans**: Individual steps or operations within a trace
- **Feedback Scores**: Evaluation results attached to traces and spans
- **Experiment Items**: Links between traces, datasets, and experiment runs

Key Classes
-----------

.. toctree::
   :maxdepth: 1

   FeedbackScoreModel
   SpanModel
   TraceModel
   ExperimentItemModel
   local_recording

Class Hierarchy
---------------

The models form a hierarchical relationship:

.. code-block:: text

    TraceModel
    ├── spans: List[SpanModel]
    │   ├── spans: List[SpanModel]  (nested spans)
    │   └── feedback_scores: List[FeedbackScoreModel]
    └── feedback_scores: List[FeedbackScoreModel]

Quick Start
-----------

Import the models:

.. code-block:: python

    from opik.message_processing.emulation.models import (
        TraceModel,
        SpanModel,
        FeedbackScoreModel,
        ExperimentItemModel
    )

Common Usage Patterns
---------------------

Task Span Evaluation
~~~~~~~~~~~~~~~~~~~~~

The primary use case for these models is in task span evaluation, where custom metrics analyze span data:

.. code-block:: python

    from opik.evaluation.metrics import BaseMetric, score_result
    from opik.message_processing.emulation.models import SpanModel

    class CustomSpanMetric(BaseMetric):
        def score(self, task_span: SpanModel) -> score_result.ScoreResult:
            # Access span properties
            span_name = task_span.name
            input_data = task_span.input
            output_data = task_span.output

            # Perform evaluation logic
            score_value = self.evaluate_span(span_name, input_data, output_data)

            return score_result.ScoreResult(
                value=score_value,
                name=self.name,
                reason=f"Evaluated span: {span_name}"
            )

Analyzing Trace Structure
~~~~~~~~~~~~~~~~~~~~~~~~~

You can traverse and analyze the hierarchical structure of traces:

.. code-block:: python

    def analyze_trace_structure(trace: TraceModel):
        print(f"Trace: {trace.name}")
        print(f"Total spans: {len(trace.spans)}")

        for span in trace.spans:
            print(f"  Span: {span.name} (type: {span.type})")

            # Analyze nested spans
            for nested_span in span.spans:
                print(f"    Nested: {nested_span.name}")

Working with Feedback Scores
~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Both traces and spans can contain feedback scores from evaluations:

.. code-block:: python

    def collect_all_scores(trace: TraceModel):
        all_scores = []

        # Collect trace-level scores
        all_scores.extend(trace.feedback_scores)

        # Collect span-level scores
        for span in trace.spans:
            all_scores.extend(span.feedback_scores)

            # Recursively collect from nested spans
            for nested_span in span.spans:
                all_scores.extend(nested_span.feedback_scores)

        return all_scores

Integration with Evaluation System
----------------------------------

These models are automatically populated and used by the Opik evaluation system:

1. **Trace Creation**: When you run ``opik.evaluate()``, traces are automatically created
2. **Span Population**: Individual function calls become spans within the trace
3. **Task Span Evaluation**: Metrics with ``task_span`` parameters receive ``SpanModel`` objects
4. **Score Attachment**: Feedback scores are automatically attached to the appropriate traces and spans

You typically don't need to create these models manually - they're generated automatically during evaluation. However, understanding their structure is essential for writing effective task span evaluation metrics.

Use Cases
---------

These models are commonly used for:

- **Custom Evaluation Metrics**: Analyzing detailed execution data in custom metrics
- **Performance Analysis**: Understanding execution patterns and performance characteristics
- **Debugging**: Investigating issues in complex operations
- **Cost Tracking**: Aggregating usage and cost information across operations
- **Quality Assessment**: Evaluating the quality of individual steps and overall operations

Module Reference
----------------

For detailed API documentation, see the following class reference pages:

- :doc:`TraceModel <../message_processing_emulation/TraceModel>`
- :doc:`SpanModel <../message_processing_emulation/SpanModel>`
- :doc:`FeedbackScoreModel <../message_processing_emulation/FeedbackScoreModel>`
- :doc:`ExperimentItemModel <../message_processing_emulation/ExperimentItemModel>`
