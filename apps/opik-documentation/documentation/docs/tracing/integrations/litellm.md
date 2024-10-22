---
sidebar_label: LiteLLM
---

# LiteLLM

[LiteLLM](https://github.com/BerriAI/litellm) allows you to call all LLM APIs using the OpenAI format [Bedrock, Huggingface, VertexAI, TogetherAI, Azure, OpenAI, Groq etc.]. There are two main ways to use LiteLLM:

1. Using the [LiteLLM Python SDK](https://docs.litellm.ai/docs/#litellm-python-sdk)
2. Using the [LiteLLM Proxy Server (LLM Gateway)](https://docs.litellm.ai/docs/#litellm-proxy-server-llm-gateway)

## Getting started

First, ensure you have both `opik` and `litellm` packages installed:

```bash
pip install opik litellm
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platfrom your API key:

```bash
opik configure
```

## Using Opik with the LiteLLM Python SDK

### Logging LLM calls

In order to log the LLM calls to Opik, you will need to create the OpikLogger callback. Once the OpikLogger callback is created and added to LiteLLM, you can make calls to LiteLLM as you normally would:

```python
from litellm.integrations.opik.opik import OpikLogger
import litellm

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="gpt-3.5-turbo",
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ]
)
```

![LiteLLM Integration](/img/cookbook/litellm_cookbook.png)

### Logging LLM calls within a tracked function

If you are using LiteLLM within a function tracked with the [`@track`](/tracing/log_traces#using-function-decorators) decorator, you will need to pass the `current_span_data` as metadata to the `litellm.completion` call:

```python
from opik import track
from opik.opik_context import get_current_span_data
from litellm.integrations.opik.opik import OpikLogger
import litellm

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

@track
def streaming_function(input):
    messages = [{"role": "user", "content": input}]
    response = litellm.completion(
        model="gpt-3.5-turbo",
        messages=messages,
        metadata = {
            "opik": {
                "current_span_data": get_current_span_data(),
                "tags": ["streaming-test"],
            },
        }
    )
    return response

response = streaming_function("Why is tracking and evaluation of LLMs important?")
chunks = list(response)
```

## Using Opik with the LiteLLM Proxy Server

### Configuring the LiteLLM Proxy Server

In order to configure the Opik logging, you will need to update the `litellm_settings` section in the LiteLLM `config.yaml` config file:

```yaml
model_list:
  - model_name: gpt-4o
    litellm_params:
      model: gpt-4o
litellm_settings:
  success_callback: ["opik"]
```

You can now start the LiteLLM Proxy Server and all LLM calls will be logged to Opik:

```bash
litellm --config config.yaml
```

### Using the LiteLLM Proxy Server

Each API call made to the LiteLLM Proxy server will now be logged to Opik:

```bash
curl -X POST http://localhost:4000/v1/chat/completions -H "Content-Type: application/json" -d '{
    "model": "gpt-4o",
    "messages": [
        {
            "role": "user",
            "content": "Hello!"
        }
    ]
}'
```
