---
sidebar_position: 4
sidebar_label: Annotate Traces
---

# Annotate Traces

Annotating traces is a crucial aspect of evaluating and improving your LLM-based applications. By systematically recording qualitative or quantitative feedback on specific interactions or entire conversation flows, you can:

1. Track performance over time
2. Identify areas for improvement
3. Compare different model versions or prompts
4. Gather data for fine-tuning or retraining
5. Provide stakeholders with concrete metrics on system effectiveness

Opik allows you to annotate traces through the SDK or the UI.

## Annotating Traces through the UI

To annotate traces through the UI, you can navigate to the trace you want to annotate in the traces page and click on the `Annotate` button. This will open a sidebar where you can add annotations to the trace.

You can annotate both traces and spans through the UI, make sure you have selected the correct span in the sidebar.

![Annotate Traces](/img/tracing/annotate_traces.png)

:::tip
In order to ensure a consistent set of feedback, you will need to define feedback definitions in the `Feedback Definitions` page which supports both numerical and categorical annotations.
:::

## Annotating traces and spans using the SDK

You can use the SDK to annotate traces and spans which can be useful both as part of the evaluation process or if you receive user feedback scores in your application.

### Annotating Traces through the SDK

Feedback scores can be logged for traces using the `log_traces_feedback_scores` method:

```python
from opik import Opik

client = Opik(project_name="my_project")

trace = client.trace(name="my_trace")

client.log_traces_feedback_scores(
    scores=[
        {"id": trace.id, "name": "overall_quality", "value": 0.85},
        {"id": trace.id, "name": "coherence", "value": 0.75},
    ]
)
```

:::tip
The `scores` argument supports an optional `reason` field that can be provided to each score. This can be used to provide a human-readable explanation for the feedback score.
:::

### Annotating Spans through the SDK

To log feedback scores for individual spans, use the `log_spans_feedback_scores` method:

```python
from opik import Opik

client = Opik()

trace = client.trace(name="my_trace")
span = trace.span(name="my_span")

client.log_spans_feedback_scores(
    scores=[
        {"id": span.id, "name": "overall_quality", "value": 0.85},
        {"id": span.id, "name": "coherence", "value": 0.75},
    ],
)
```

:::note
The `FeedbackScoreDict` class supports an optional `reason` field that can be used to provide a human-readable explanation for the feedback score.
:::

### Using Opik's built-in evaluation metrics

Computing feedback scores can be challenging due to the fact that Large Language Models can return unstructured text and non-deterministic outputs. In order to help with the computation of these scores, Opik provides some built-in evaluation metrics.

Opik's built-in evaluation metrics are broken down into two main categories:
1. Heuristic metrics
2. LLM as a judge metrics

### Heuristic Metrics

Heuristic metrics are use rule-based or statistical methods that can be used to evaluate the output of LLM models.

Opik supports a variety of heuristic metrics including:
* `EqualsMetric`
* `RegexMatchMetric`
* `ContainsMetric`
* `IsJsonMetric`
* `PerplexityMetric`
* `BleuMetric`
* `RougeMetric`

You can find a full list of metrics in the [Heuristic Metrics](/evaluation/metrics/heuristic_metrics.md) section.

These can be used by calling:

```python
from opik.evaluation.metrics import Contains

metric = Contains()
score = metric.score(
    output="The quick brown fox jumps over the lazy dog.",
    expected_output="The quick brown fox jumps over the lazy dog."
)
```

### LLM as a Judge Metrics

For LLM outputs that cannot be evaluated using heuristic metrics, you can use LLM as a judge metrics. These metrics are based on the idea of using an LLM to evaluate the output of another LLM.

Opik supports many different LLM as a Judge metrics out of the box including:
* `FactualityMetric`
* `ModerationMetric`
* `HallucinationMetric`
* `AnswerRelevanceMetric`
* `ContextRecallMetric`
* `ContextPrecisionMetric`

You can find a full list of supported metrics in the [Metrics Overview](/evaluation/metrics/overview.md) section.
