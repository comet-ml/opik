---
sidebar_position: 100
sidebar_label: Custom Metric
---

# Custom Metric

Opik allows you to define your own metrics. This is useful if you have a specific metric that is not already implemented.

## Defining a custom metric

To define a custom metric, you need to subclass the `Metric` class and implement the `score` method and an optional `ascore` method:

```python
from opik.evaluation.metrics import base_metric, score_result

class MyCustomMetric(base_metric.BaseMetric):
    def __init__(self, name: str):
        self.name = name

    def score(self, input: str, output: str, **ignored_kwargs: Any):
        # Add you logic here

        return score_result.ScoreResult(
            value=0,
            name=self.name,
            reason="Optional reason for the score"
        )
```

The `score` method should return a `ScoreResult` object. The `ascore` method is optional and can be used to compute the asynchronously if needed.

:::tip
You can also return a list of `ScoreResult` objects as part of your custom metric. This is useful if you want to return multiple scores for a given input and output pair.
:::

This metric can now be used in the `evaluate` function as explained here: [Evaluating LLMs](/evaluation/evaluate_your_llm).
