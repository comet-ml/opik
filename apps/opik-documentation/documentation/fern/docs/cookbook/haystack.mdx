# Using Opik with Haystack

[Haystack](https://docs.haystack.deepset.ai/docs/intro) is an open-source framework for building production-ready LLM applications, retrieval-augmented generative pipelines and state-of-the-art search systems that work intelligently over large document collections.

In this guide, we will showcase how to integrate Opik with Haystack so that all the Haystack calls are logged as traces in Opik.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=haystack&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=haystack&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=haystack&utm_campaign=opik) for more information.


```python
%pip install --upgrade --quiet opik haystack-ai
```


```python
import opik

opik.configure(use_local=False)
```


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Creating the Haystack pipeline

In this example, we will create a simple pipeline that uses a prompt template to translate text to German.

To enable Opik tracing, we will:
1. Enable content tracing in Haystack by setting the environment variable `HAYSTACK_CONTENT_TRACING_ENABLED=true`
2. Add the `OpikConnector` component to the pipeline

Note: The `OpikConnector` component is a special component that will automatically log the traces of the pipeline as Opik traces, it should not be connected to any other component.


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
pipe.add_component("tracer", OpikConnector("Chat example"))

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

trace_id = response["tracer"]["trace_id"]
print(f"Trace ID: {trace_id}")
print(response["llm"]["replies"][0])
```

The trace is now logged to the Opik platform:

![Haystack trace](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/haystack_trace_cookbook.png)

## Advanced usage

### Ensuring the trace is logged

By default the `OpikConnector` will flush the trace to the Opik platform after each component in a thread blocking way. As a result, you may disable flushing the data after each component by setting the `HAYSTACK_OPIK_ENFORCE_FLUSH` environent variable to `false`.

**Caution**: Disabling this feature may result in data loss if the program crashes before the data is sent to Opik. Make sure you will call the `flush()` method explicitly before the program exits:


```python
from haystack.tracing import tracer

tracer.actual_tracer.flush()
```

### Getting the trace ID

If you would like to log additional information to the trace you will need to get the trace ID. You can do this by the `tracer` key in the response of the pipeline:


```python
response = pipe.run(
    data={
        "prompt_builder": {
            "template_variables": {"location": "Berlin"},
            "template": messages,
        }
    }
)

trace_id = response["tracer"]["trace_id"]
print(f"Trace ID: {trace_id}")
```
