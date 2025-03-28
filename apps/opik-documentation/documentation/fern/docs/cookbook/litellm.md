# Using Opik with LiteLLM

Lite allows you to call all LLM APIs using the OpenAI format [Bedrock, Huggingface, VertexAI, TogetherAI, Azure, OpenAI, Groq etc.]. You can learn more about LiteLLM [here](https://github.com/BerriAI/litellm).

There are two main approaches to using LiteLLM, either using the `litellm` [python library](https://docs.litellm.ai/docs/#litellm-python-sdk) that will query the LLM API for you or by using the [LiteLLM proxy server](https://docs.litellm.ai/docs/#litellm-proxy-server-llm-gateway). In this cookbook we will focus on the first approach but you can learn more about using Opik with the LiteLLM proxy server in our [documentation](https://www.comet.com/docs/opik/tracing/integrations/litellm).

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik litellm
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

In order to use LiteLLM, we will configure the OpenAI API Key, if you are using any other providers you can replace this with the required API key:


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Logging traces

In order to log traces to Opik, you will need to set the `opik` callback:


```python
from litellm.integrations.opik.opik import OpikLogger
from opik.opik_context import get_current_span_data
from opik import track
import litellm

os.environ["OPIK_PROJECT_NAME"] = "litellm-integration-demo"
opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]
```

Every LiteLLM call will now be logged to Opik:


```python
response = litellm.completion(
    model="gpt-3.5-turbo",
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ],
)

print(response.choices[0].message.content)
```

The trace will now be viewable in the Opik platform:

![OpenAI Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/litellm_cookbook.png)

## Logging LLM calls within a tracked function


If you are using LiteLLM within a function tracked with the `@track` decorator, you will need to pass the `current_span_data` as metadata to the `litellm.completion` call:



```python
@track
def streaming_function(input):
    messages = [{"role": "user", "content": input}]
    response = litellm.completion(
        model="gpt-3.5-turbo",
        messages=messages,
        metadata={
            "opik": {
                "current_span_data": get_current_span_data(),
                "tags": ["streaming-test"],
            },
        },
    )
    return response


response = streaming_function("Why is tracking and evaluation of LLMs important?")
chunks = list(response)
```
