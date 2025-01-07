---
sidebar_label: watsonx
description: Describes how to track watsonx LLM calls using Opik
test_code_snippets: false
---

# watsonx

[watsonx](https://www.ibm.com/products/watsonx-ai) is a next generation enterprise studio for AI builders to train, validate, tune and deploy AI models.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/watsonx.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

### Configuring Opik

To start tracking your watsonx LLM calls, you can use our [LiteLLM integration](/docs/opik/tracing/integrations/litellm). You'll need to have both the `opik` and `litellm` packages installed. You can install them using pip:

```bash
pip install opik litellm
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platfrom your API key:

```bash
opik configure
```

:::info

If you’re unable to use our LiteLLM integration with watsonx, please [open an issue](https://github.com/comet-ml/opik/issues/new/choose)

:::

### Configuring watsonx

In order to configure watsonx, you will need to have:

- The endpoint URL: Documentation for this parameter can be found [here](https://cloud.ibm.com/apidocs/watsonx-ai#endpoint-url)
- Watsonx API Key: Documentation for this parameter can be found [here](https://cloud.ibm.com/docs/account?topic=account-userapikey&interface=ui)
- Watsonx Token: Documentation for this parameter can be found [here](https://cloud.ibm.com/docs/account?topic=account-iamtoken_from_apikey#iamtoken_from_apikey)
- (Optional) Watsonx Project ID: Can be found in the Manage section of your project.

Once you have these, you can set them as environment variables:

```python
import os

os.environ["WATSONX_ENDPOINT_URL"] = "" # Base URL of your WatsonX instance
os.environ["WATSONX_API_KEY"] = "" # IBM cloud API key
os.environ["WATSONX_TOKEN"] = "" # IAM auth token

# Optional
# os.environ["WATSONX_PROJECT_ID"] = "" # Project ID of your WatsonX instance
```

## Logging LLM calls

In order to log the LLM calls to Opik, you will need to create the OpikLogger callback. Once the OpikLogger callback is created and added to LiteLLM, you can make calls to LiteLLM as you normally would:

```python
from litellm.integrations.opik.opik import OpikLogger
import litellm

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="watsonx/ibm/granite-13b-chat-v2",
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ]
)
```

![watsonx Integration](/img/cookbook/watsonx_trace_cookbook.png)

## Logging LLM calls within a tracked function

If you are using LiteLLM within a function tracked with the [`@track`](/tracing/log_traces#using-function-decorators) decorator, you will need to pass the `current_span_data` as metadata to the `litellm.completion` call:

```python
@track
def generate_story(prompt):
    response = litellm.completion(
        model="watsonx/ibm/granite-13b-chat-v2",
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": get_current_span_data(),
            },
        },
    )
    return response.choices[0].message.content


@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    response = litellm.completion(
        model="watsonx/ibm/granite-13b-chat-v2",
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": get_current_span_data(),
            },
        },
    )
    return response.choices[0].message.content


@track
def generate_opik_story():
    topic = generate_topic()
    story = generate_story(topic)
    return story


generate_opik_story()
```

![watsonx Integration](/img/cookbook/watsonx_trace_decorator_cookbook.png)
