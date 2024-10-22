# Using Opik with Ollama

[Ollama](https://ollama.com/) allows users to run, interact with, and deploy AI models locally on their machines without the need for complex infrastructure or cloud dependencies.

In this notebook, we will showcase how to log Ollama LLM calls using Opik by utilizing either the OpenAI or LangChain libraries.

## Getting started

### Configure Ollama

In order to interact with Ollama from Python, we will to have Ollama running on our machine. You can learn more about how to install and run Ollama in the [quickstart guide](https://github.com/ollama/ollama/blob/main/README.md#quickstart).

### Configuring Opik

Opik is available as a fully open source local installation or using Comet.com as a hosted solution. The easiest way to get started with Opik is by creating a free Comet account at comet.com.

If you'd like to self-host Opik, you can learn more about the self-hosting options [here](https://www.comet.com/docs/opik/self-host/overview).

In addition, you will need to install and configure the Opik Python package:


```python
%pip install --upgrade --quiet opik

import opik

opik.configure()
```

## Tracking Ollama calls made with OpenAI

Ollama is compatible with the OpenAI format and can be used with the OpenAI Python library. You can therefore leverage the Opik integration for OpenAI to trace your Ollama calls:



```python
from openai import OpenAI
from opik.integrations.openai import track_openai

import os

os.environ["OPIK_PROJECT_NAME"] = "ollama-integration"

# Create an OpenAI client
client = OpenAI(
    base_url="http://localhost:11434/v1/",
    # required but ignored
    api_key="ollama",
)

# Log all traces made to with the OpenAI client to Opik
client = track_openai(client)

# call the local ollama model using the OpenAI client
chat_completion = client.chat.completions.create(
    messages=[
        {
            "role": "user",
            "content": "Say this is a test",
        }
    ],
    model="llama3.1",
)

print(chat_completion.choices[0].message.content)
```

Your LLM call is now traced and logged to the Opik platform.

## Tracking Ollama calls made with LangChain

In order to trace Ollama calls made with LangChain, you will need to first install the `langchain-ollama` package:


```python
%pip install --quiet --upgrade langchain-ollama
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

![Ollama trace in Opik](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/ollama_cookbook.png)
