---
sidebar_position: 3
sidebar_label: LangChain
---

# LangChain

Comet provides seamless integration with LangChain, allowing you to easily log and trace your LangChain-based applications. By using the `OpikTracer` callback, you can automatically capture detailed information about your LangChain runs, including inputs, outputs, and metadata for each step in your chain.

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

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platfrom your API key:

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

# The OpikTracer will automatically log the run and its details to Comet
```

This example demonstrates how to create a LangChain chain with a `OpikTracer` callback. When you run the chain with a prompt, the `OpikTracer` will automatically log the run and its details to Comet, including the input prompt, the output, and metadata for each step in the chain.

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

You can use the `created_traces` method to access the trace IDs collected by the `OpikTracer` callback:

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()

# Calling Langchain object

traces = opik_tracer.created_traces()
print([trace.id for trace in traces])
```

This can be especially useful if you would like to update or log feedback scores for traces logged using the OpikTracer.

## Advanced usage

The `OpikTracer` object has a `flush` method that can be used to make sure that all traces are logged to the Comet platform before you exit a script. This method will return once all traces have been logged or if the timeout is reach, whichever comes first.

```python
from opik.integrations.langchain import OpikTracer

opik_tracer = OpikTracer()
opik_tracer.flush()
```
