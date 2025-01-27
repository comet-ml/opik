---
sidebar_label: Production Monitoring
description: Describes how to monitor your LLM applications in production using Opik
---

# Production Monitoring

Opik has been designed from the ground up to support high volumes of traces making it the ideal tool for monitoring your production LLM applications.

You can use the Opik dashboard to review your feedback scores, trace count and tokens over time at both a daily and hourly granularity.

![Opik monitoring dashboard](/img/tracing/opik_monitoring_dashboard.png)

In addition to viewing scores over time, you can also view the average feedback scores for all the traces in your project from the traces table.

## Logging feedback scores

To monitor the performance of your LLM application, you can log feedback scores using the [Python SDK and through the UI](/tracing/annotate_traces.md).

### Defining online evaluation metrics

You can define LLM as a Judge metrics in the Opik platform that will automatically score all, or a subset, of your production traces. You can find more information about how to define LLM as a Judge metrics in the [Online evaluation](/production/rules.md) section.

<!-- Add gif of the online evaluation feature -->

Once a rule is defined, Opik will score all the traces in the project and allow you to track these feedback scores over time.

:::tip
In addition to allowing you to define LLM as a Judge metrics, Opik will soon allow you to define Python metrics to give you even more control over the feedback scores.
:::

### Manually logging feedback scores alongside traces

Feedback scores can be logged while you are logging traces:

```python
from opik import track, opik_context

@track
def llm_chain(input_text):
    # LLM chain code
    # ...

    # Update the trace
    opik_context.update_current_trace(
        feedback_scores=[
            {"name": "user_feedback", "value": 1.0, "reason": "The response was helpful and accurate."}
        ]
    )
```

### Updating traces with feedback scores

You can also update traces with feedback scores after they have been logged. For this we are first going to fetch all the traces using the search API and then update the feedback scores for the traces we want to annotate.

#### Fetching traces using the search API

You can use the [`Opik.search_traces`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.search_traces) method to fetch all the traces you want to annotate.

```python
import opik

opik_client = opik.Opik()

traces = opik_client.search_traces(
    project_name="Default Project"
)
```

:::tip

The `search_traces` method allows you to fetch traces based on any of trace attributes, you can learn more about the different search parameters in the [search traces documentation](/tracing/export_data.md).

:::

#### Updating feedback scores

Once you have fetched the traces you want to annotate, you can update the feedback scores using the [`Opik.log_traces_feedback_scores`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.log_traces_feedback_scores) method.

```python pytest_codeblocks_skip="true"
for trace in traces:
    opik_client.log_traces_feedback_scores(
        project_name="Default Project",
        feedback_scores=[{"id": trace.id, "name": "user_feedback", "value": 1.0, "reason": "The response was helpful and accurate."}],
    )
```

You will now be able to see the feedback scores in the Opik dashboard and track the changes over time.
