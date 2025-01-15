---
sidebar_label: Haystack
description: Describes how to track Haystack pipeline runs using Opik
---

# Haystack

[Haystack](https://docs.haystack.deepset.ai/docs/intro) is an open-source framework for building production-ready LLM applications, retrieval-augmented generative pipelines and state-of-the-art search systems that work intelligently over large document collections.

Opik integrates with Haystack to log traces for all Haystack pipelines.

## Getting started

First, ensure you have both `opik` and `haystack-ai` installed:

```bash
pip install opik haystack-ai
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash pytest_codeblocks_skip=true
opik configure
```

## Logging Haystack pipeline runs

To log a Haystack pipeline run, you can use the [`OpikConnector`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/haystack/OpikConnector.html). This connector will log the pipeline run to the Opik platform and add a `tracer` key to the pipeline run response with the trace ID:

```python
import os

os.environ["HAYSTACK_CONTENT_TRACING_ENABLED"] = "true"

from haystack import Pipeline
from haystack.components.builders import ChatPromptBuilder
from haystack.components.generators.chat import OpenAIChatGenerator
from haystack.dataclasses import ChatMessage

from opik.integrations.haystack import OpikConnector

pipe = Pipeline()

# Add the OpikConnector component to the pipeline
pipe.add_component(
    "tracer", OpikConnector("Chat example")
)

# Continue building the pipeline
pipe.add_component("prompt_builder", ChatPromptBuilder())
pipe.add_component("llm", OpenAIChatGenerator(model="gpt-3.5-turbo"))

pipe.connect("prompt_builder.prompt", "llm.messages")

messages = [
    ChatMessage.from_system(
        "Always respond in German even if some input data is in other languages."
    ),
    ChatMessage.from_user("Tell me about {{location}}"),
]

response = pipe.run(
    data={
        "prompt_builder": {
            "template_variables": {"location": "Berlin"},
            "template": messages,
        }
    }
)

print(response["llm"]["replies"][0])
```

Each pipeline run will now be logged to the Opik platform:

![Haystack](/img/cookbook/haystack_trace_cookbook.png)

:::tip

In order to ensure the traces are correctly logged, make sure you set the environment variable `HAYSTACK_CONTENT_TRACING_ENABLED` to `true` before running the pipeline.

:::

## Advanced usage

### Disabling automatic flushing of traces

By default the `OpikConnector` will flush the trace to the Opik platform after each component in a thread blocking way. As a result, you may want to disable flushing the data after each component by setting the `HAYSTACK_OPIK_ENFORCE_FLUSH` environent variable to `false`.

In order to make sure that all traces are logged to the Opik platform before you exit a script, you can use the `flush` method:

```python
from opik.integrations.haystack import OpikConnector
from haystack.tracing import tracer
from haystack import Pipeline

pipe = Pipeline()

# Add the OpikConnector component to the pipeline
pipe.add_component(
    "tracer", OpikConnector("Chat example")
)

# Pipeline definition
tracer.actual_tracer.flush()
```

:::warning

Disabling this feature may result in data loss if the program crashes before the data is sent to Opik. Make sure you will call the `flush()` method explicitly before the program exits.

:::

### Updating logged traces

The `OpikConnector` returns the logged trace ID in the pipeline run response. You can use this ID to update the trace with feedback scores or other metadata:

```python pytest_codeblocks_skip=true
import opik

response = pipe.run(
    data={
        "prompt_builder": {
            "template_variables": {"location": "Berlin"},
            "template": messages,
        }
    }
)

# Get the trace ID from the pipeline run response
trace_id = response["tracer"]["trace_id"]

# Log the feedback score
opik_client = opik.Opik()
opik_client.log_traces_feedback_scores([
    {"id": trace_id, "name": "user-feedback", "value": 0.5}
])
```
