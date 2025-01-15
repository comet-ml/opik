---
sidebar_label: LangChain
description: Describes how to use Opik with LangChain
---

# LangChain

Opik provides seamless integration with LangChain, allowing you to easily log and trace your LangChain-based applications. By using the `OpikTracer` callback, you can automatically capture detailed information about your LangChain runs, including inputs, outputs, and metadata for each step in your chain.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/langchain.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

To use the `OpikTracer` with LangChain, you'll need to have both the `opik` and `langchain` packages installed. You can install them using pip:

```bash
pip install opik langchain langchain_openai
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

## Using OpikTracer

Here's a basic example of how to use the `OpikTracer` callback with a LangChain chain:

```python
from langchain.chains import LLMChain
from langchain_openai import OpenAI
from langchain.prompts import PromptTemplate
from opik.integrations.langchain import OpikTracer

# Initialize the tracer
opik_tracer = OpikTracer()

# Create the LLM Chain using LangChain
llm = OpenAI(temperature=0)

prompt_template = PromptTemplate(
    input_variables=["input"],
    template="Translate the following text to French: {input}"
)

llm_chain = LLMChain(llm=llm, prompt=prompt_template)

# Generate the translations
translation = llm_chain.run("Hello, how are you?", callbacks=[opik_tracer])
print(translation)

# The OpikTracer will automatically log the run and its details to Opik
```

This example demonstrates how to create a LangChain chain with a `OpikTracer` callback. When you run the chain with a prompt, the `OpikTracer` will automatically log the run and its details to Opik, including the input prompt, the output, and metadata for each step in the chain.

## Settings tags and metadata

You can also customize the `OpikTracer` callback to include additional metadata or logging options. For example:

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer(
    tags=["langchain"],
    metadata={"use-case": "documentation-example"}
)
```

## Accessing logged traces

You can use the [`created_traces`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/langchain/OpikTracer.html) method to access the traces collected by the `OpikTracer` callback:

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()

# Calling Langchain object
traces = opik_tracer.created_traces()
print([trace.id for trace in traces])
```

The traces returned by the `created_traces` method are instances of the [`Trace`](https://www.comet.com/docs/opik/python-sdk-reference/Objects/Trace.html#opik.api_objects.trace.Trace) class, which you can use to update the metadata, feedback scores and tags for the traces.

### Accessing the content of logged traces

In order to access the content of logged traces you will need to use the [`Opik.get_trace_content`](https://www.comet.com/docs/opik/python-sdk-reference/Opik.html#opik.Opik.get_trace_content) method:

```python
import opik
from opik.integrations.langchain import OpikTracer
opik_client = opik.Opik()

opik_tracer = OpikTracer()


# Calling Langchain object

# Getting the content of the logged traces
traces = opik_tracer.created_traces()
for trace in traces:
    content = opik_client.get_trace_content(trace.id)
    print(content)
```

### Updating and scoring logged traces

You can update the metadata, feedback scores and tags for traces after they are created. For this you can use the `created_traces` method to access the traces and then update them using the [`update`](https://www.comet.com/docs/opik/python-sdk-reference/Objects/Trace.html#opik.api_objects.trace.Trace.update) method and the [`log_feedback_score`](https://www.comet.com/docs/opik/python-sdk-reference/Objects/Trace.html#opik.api_objects.trace.Trace.log_feedback_score) method:

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()

# Calling Langchain object

traces = opik_tracer.created_traces()

for trace in traces:
    trace.update(tag=["langchain"])
    trace.log_feedback_score(name="user-feedback", value=0.5)
```

## Advanced usage

The `OpikTracer` object has a `flush` method that can be used to make sure that all traces are logged to the Opik platform before you exit a script. This method will return once all traces have been logged or if the timeout is reach, whichever comes first.

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()
opik_tracer.flush()
```
