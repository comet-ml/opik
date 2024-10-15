# Using Opik with Groq

Opik integrates with Groq to provide a simple way to log traces for all Groq LLM calls. This works for all Groq models.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik groq litellm
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will set up our OpenAI API keys.


```python
import os
import getpass

if "GROQ_API_KEY" not in os.environ:
    os.environ["GROQ_API_KEY"] = getpass.getpass("Enter your Groq API key: ")
```

## Logging traces

In order to log traces to Opik, we will be using the OpikTracer from the LiteLLM integration.


```python
import litellm
from litellm.integrations.opik.opik import OpikLogger

os.environ["OPIK_PROJECT_NAME"] = "groq-integration-demo"
opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

prompt = """
Write a short two sentence story about Opik.
"""

response = litellm.completion(
    model="groq/llama3-8b-8192",
    messages=[
        {"role": "user", "content": prompt}
    ],
)

print(response.choices[0].message.content)
```

The prompt and response messages are automatically logged to Opik and can be viewed in the UI.

![LiteLLM Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/groq_trace_cookbook.png)


