---
sidebar_position: 5
sidebar_label: Log Feedback Scores
---

# Log Feedback Scores

Logging feedback scores is a crucial aspect of evaluating and improving your LLM-based applications. By systematically recording qualitative or quantitative feedback on specific interactions or entire conversation flows, you can:

1. Track performance over time
2. Identify areas for improvement
3. Compare different model versions or prompts
4. Gather data for fine-tuning or retraining
5. Provide stakeholders with concrete metrics on system effectiveness

Comet provides powerful tools to log feedback scores for both individual spans (specific interactions) and entire traces (complete conversation flows). This granular approach allows you to pinpoint exactly where your system excels or needs improvement.

## Logging Feedback Scores

Feedback scores can be logged at both a trace and a span level using `log_traces_feedback_scores` and `log_spans_feedback_scores` respectively.

### Logging Feedback Scores for Traces

To log feedback scores for entire traces, use the `log_traces_feedback_scores` method:

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

:::note
The `scores` argument supports an optional `reason` field that can be provided to each score. This can be used to provide a human-readable explanation for the feedback score.
:::

### Logging Feedback Scores for Spans

To log feedback scores for individual spans, use the `log_spans_feedback_scores` method:

```python
from opik import Opik

client = Opik()

trace = client.trace(name="my_trace")
span = trace.span(name="my_span")

comet.log_spans_feedback_scores(
    scores=[
        {"id": span.id, "name": "overall_quality", "value": 0.85},
        {"id": span.id, "name": "coherence", "value": 0.75},
    ],
)
```

:::note
The `FeedbackScoreDict` class supports an optional `reason` field that can be used to provide a human-readable explanation for the feedback score.
:::

## Computing Feedback Scores

Computing feedback scores can be challenging due to the fact that Large Language Models can return unstructured text and non-deterministic outputs. In order to help with the computation of these scores, Comet provides some built-in evaluation metrics.

Comet's built-in evaluation metrics are broken down into two main categories:
1. Heuristic metrics
2. LLM as a judge metrics

### Heuristic Metrics

Heuristic metrics are use rule-based or statistical methods that can be used to evaluate the output of LLM models.

Comet supports a variety of heuristic metrics including:
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

Comet supports many different LLM as a Judge metrics out of the box including:
* `FactualityMetric`
* `ModerationMetric`
* `HallucinationMetric`
* `AnswerRelevanceMetric`
* `ContextRecallMetric`
* `ContextPrecisionMetric`
* `ContextRelevancyMetric`

You can find a full list of metrics in the [LLM as a Judge Metrics](/evaluation/metrics/llm_as_a_judge_metrics.md) section.
