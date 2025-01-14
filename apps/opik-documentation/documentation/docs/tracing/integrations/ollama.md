---
sidebar_label: Ollama
description: Describes how to track Ollama LLM calls using Opik
pytest_codeblocks_skip: true
---

# Ollama

[Ollama](https://ollama.com/) allows users to run, interact with, and deploy AI models locally on their machines without the need for complex infrastructure or cloud dependencies.

There are multiple ways to interact with Ollama from Python including but not limited to the [ollama python package](https://pypi.org/project/ollama/), [LangChain](https://python.langchain.com/docs/integrations/providers/ollama/) or by using the [OpenAI library](https://github.com/ollama/ollama/blob/main/docs/openai.md). We will cover how to trace your LLM calls for each of these methods.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/ollama.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting started

### Configure Ollama

Before starting, you will need to have an Ollama instance running. You can install Ollama by following the [quickstart guide](https://github.com/ollama/ollama/blob/main/README.md#quickstart) which will automatically start the Ollama API server. If the Ollama server is not running, you can start it using `ollama serve`.

Once Ollama is running, you can download the llama3.1 model by running `ollama pull llama3.1`. For a full list of models available on Ollama, please refer to the [Ollama library](https://ollama.com/library).

### Configure Opik

You will also need to have Opik installed. You can install and configure it by running the following command:

```bash
pip install --upgrade --quiet opik

opik configure
```

:::tip
Opik is fully open-source and can be run locally or through the Opik Cloud platform. You can learn more about hosting Opik on your own infrastructure in the [self-hosting guide](/docs/self-host/overview.md).
:::

## Tracking Ollama calls made with Ollama Python Package

To get started you will need to install the Ollama Python package:

```bash
pip install --quiet --upgrade ollama
```

We will then utilize the `track` decorator to log all the traces to Opik:

```python
import ollama
from opik import track, opik_context

@track(tags=['ollama', 'python-library'])
def ollama_llm_call(user_message: str):
    # Create the Ollama model
    response = ollama.chat(model='llama3.1', messages=[
        {
            'role': 'user',
            'content': user_message,
        },
    ])

    opik_context.update_current_span(
        metadata={
            'model': response['model'],
            'eval_duration': response['eval_duration'],
            'load_duration': response['load_duration'],
            'prompt_eval_duration': response['prompt_eval_duration'],
            'prompt_eval_count': response['prompt_eval_count'],
            'done': response['done'],
            'done_reason': response['done_reason'],
        },
        usage={
            'completion_tokens': response['eval_count'],
            'prompt_tokens': response['prompt_eval_count'],
            'total_tokens': response['eval_count'] + response['prompt_eval_count']
        }
    )
    return response['message']

ollama_llm_call("Say this is a test")
```

The trace will now be displayed in the Opik platform.

## Tracking Ollama calls made with OpenAI

Ollama is compatible with the OpenAI format and can be used with the OpenAI Python library. You can therefore leverage the Opik integration for OpenAI to trace your Ollama calls:

```python
from openai import OpenAI
from opik.integrations.openai import track_openai

# Create an OpenAI client
client = OpenAI(
    base_url='http://localhost:11434/v1/',

    # required but ignored
    api_key='ollama',
)

# Log all traces made to with the OpenAI client to Opik
client = track_openai(client)

# call the local ollama model using the OpenAI client
chat_completion = client.chat.completions.create(
    messages=[
        {
            'role': 'user',
            'content': 'Say this is a test',
        }
    ],
    model='llama3.1',
)
```

The local LLM call is now traced and logged to Opik.

## Tracking Ollama calls made with LangChain

In order to trace Ollama calls made with LangChain, you will need to first install the `langchain-ollama` package:

```bash
pip install --quiet --upgrade langchain-ollama langchain
```

You will now be able to use the `OpikTracer` class to log all your Ollama calls made with LangChain to Opik:

```python
from langchain_ollama import ChatOllama
from opik.integrations.langchain import OpikTracer

# Create the Opik tracer
opik_tracer = OpikTracer(tags=["langchain", "ollama"])

# Create the Ollama model and configure it to use the Opik tracer
llm = ChatOllama(
    model="llama3.1",
    temperature=0,
).with_config({"callbacks": [opik_tracer]})

# Call the Ollama model
messages = [
    (
        "system",
        "You are a helpful assistant that translates English to French. Translate the user sentence.",
    ),
    (
        "human",
        "I love programming.",
    ),
]
ai_msg = llm.invoke(messages)
ai_msg
```

You can now go to the Opik app to see the trace:

![ollama](/img/cookbook/ollama_cookbook.png)
