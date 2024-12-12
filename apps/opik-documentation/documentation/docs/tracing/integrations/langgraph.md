---
sidebar_label: LangGraph
---

# LangGraph

Opik provides a seamless integration with LangGraph, allowing you to easily log and trace your LangGraph-based applications. By using the `OpikTracer` callback, you can automatically capture detailed information about your LangGraph graph executions during both development and production.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langgraph.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

To use the [`OpikTracer`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/langchain/OpikTracer.html) with LangGraph, you'll need to have both the `opik` and `langgraph` packages installed. You can install them using pip:

```bash
pip install opik langgraph
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platfrom your API key:

```bash
opik configure
```

## Using the OpikTracer

You can use the [`OpikTracer`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/langchain/OpikTracer.html) callback with any LangGraph graph by passing it in as an argument to the `stream` or `invoke` functions:

```python
from opik.integrations.langchain import OpikTracer

# create your LangGraph graph
graph = ...
app = graph.compile(...)

opik_tracer = OpikTracer(graph=app.get_graph(xray=True))

# Pass the OpikTracer callback to the Graph.stream function
for s in app.stream({"messages": [HumanMessage(content = QUESTION)]},
                      config={"callbacks": [opik_tracer]}):
    print(s)

# Pass the OpikTracer callback to the Graph.invoke function
result = app.invoke({"messages": [HumanMessage(content = QUESTION)]},
                      config={"callbacks": [opik_tracer]})
```

Once the OpikTracer is configured, you will start to see the traces in the Opik UI:

![langgraph](/img/cookbook/langgraph_cookbook.png)

## Updating logged traces

You can use the [`OpikTracer.created_traces`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/langchain/OpikTracer.html#opik.integrations.langchain.OpikTracer.created_traces) method to access the trace IDs collected by the OpikTracer callback:

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()

# Calling LangGraph stream or invoke functions

traces = opik_tracer.created_traces()
print([trace.id for trace in traces])
```

These can then be used with the [`Opik.log_traces_feedback_scores`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.log_traces_feedback_scores) method to update the logged traces.

## Advanced usage

The `OpikTracer` object has a `flush` method that can be used to make sure that all traces are logged to the Opik platform before you exit a script. This method will return once all traces have been logged or if the timeout is reach, whichever comes first.

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()
opik_tracer.flush()
```
