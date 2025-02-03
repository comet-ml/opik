---
sidebar_label: Cost Tracking
description: Describes how to track and monitor costs for your LLM applications using Opik
pytest_codeblocks_skip: true
---

# Cost Tracking

Opik has been designed to track and monitor costs for your LLM applications by measuring token usage across all traces. Using the Opik dashboard, you can analyze spending patterns and quickly identify cost anomalies. All costs across Opik are estimated and displayed in USD.

## Monitoring Costs in the Dashboard

You can use the Opik dashboard to review costs at three levels: spans, traces, and projects. Each level provides different insights into your application's cost structure.

### Span-Level Costs

Individual spans show the computed costs (in USD) for each LLM spans of your traces:

![Detailed cost breakdown for individual spans in the Traces and LLM calls view](/img/tracing/cost_tracking_span.png)

### Trace-Level Costs

Opik automatically aggregates costs from all spans within a trace to compute total trace costs:

![Total cost aggregation at the trace level](/img/tracing/cost_tracking_trace_view.png)

### Project-Level Analytics

Track your overall project costs in:

1. The main project view, through the Estimated Cost column:
   ![Project-wide cost overview](/img/tracing/cost_tracking_project.png)

2. The project Metrics tab, which shows cost trends over time:
   ![Detailed cost metrics and analytics](/img/tracing/cost_tracking_project_metrics.png)

## Retrieving Costs Programmatically

You can retrieve the estimated cost programmatically for both spans and traces. Note that the cost will be `None` if the span or trace used an unsupported model. See [Exporting Traces and Spans](./export_data.md) for more ways of exporting traces and spans.

### Retrieving Span Costs

```python
import opik

client = opik.Opik()

span = client.get_span_content("<SPAN_ID>")
# Returns estimated cost in USD, or None for unsupported models
print(span.total_estimated_cost)
```

### Retrieving Trace Costs

```python
import opik

client = opik.Opik()

trace = client.get_trace_content("<TRACE_ID>")
# Returns estimated cost in USD, or None for unsupported models
print(trace.total_estimated_cost)
```

## Manually Setting Span Costs

For cases where you need to set a custom cost or when using an unsupported model, you can manually set the cost of a span using `update_current_span`. Note that manually setting a cost will override any automatically computed cost by Opik:

```python
from opik.opik_context import update_current_span

# Inside a span context
update_current_span(total_cost=0.05)  # Cost in USD will override any automatic cost calculation
```

This is particularly useful when:
- Using models or providers not yet supported by automatic cost tracking
- You have a custom pricing agreement with your provider
- You want to track additional costs beyond model usage

## Supported Models and Integrations

Opik currently calculates costs automatically for:
- [OpenAI Integration](./integrations/openai.md) with Text Models hosted on openai.com
- [Langchain Integration](./integrations/langchain.md) with Vertex AI Gemini text generation models

:::tip
We are actively expanding our cost tracking support. Need support for additional models or providers? Please [open a feature request](https://github.com/comet-ml/opik/issues) to help us prioritize development.
:::
