# Using Opik with [PROVIDER_NAME]

[Brief description of the provider and what it's used for. Example: "[PROVIDER_NAME] is a fast AI inference platform" or "[PROVIDER_NAME] provides state-of-the-art large language models"]

<!-- Include this section only if a Colab notebook exists for this integration -->
<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">
    You can check out the Colab Notebook if you'd like to jump straight to the code:
  </span>
  <a
    className="no-external-icon"
    href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/[provider_notebook_name].ipynb"
    target="_blank"
    rel="noopener noreferrer"
  >
    <img
      src="https://colab.research.google.com/assets/colab-badge.svg"
      alt="Open In Colab"
      style="vertical-align: middle;"
    />
  </a>
</div>

## Getting Started

### Configuring Opik

To start tracking your [PROVIDER_NAME] LLM calls, you can use our [LiteLLM integration](/tracing/integrations/litellm). You'll need to have both the `opik` and `litellm` packages installed. You can install them using pip:

```bash
pip install opik litellm
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

<Info>

If you're unable to use our LiteLLM integration with [PROVIDER_NAME], please [open an issue](https://github.com/comet-ml/opik/issues/new/choose)

</Info>

### Configuring [PROVIDER_NAME]

In order to configure [PROVIDER_NAME], you will need to have:

- Your [PROVIDER_NAME] API Key: You can create and manage your [PROVIDER_NAME] API Keys on [this page]([provider_api_key_url]).

Once you have these, you can set them as environment variables:

```python
import os

os.environ["[PROVIDER_API_KEY_NAME]"] = ""  # Your [PROVIDER_NAME] API Key
```

## Logging LLM calls

In order to log the LLM calls to Opik, you will need to create the OpikLogger callback. Once the OpikLogger callback is created and added to LiteLLM, you can make calls to LiteLLM as you normally would:

```python
from litellm.integrations.opik.opik import OpikLogger
import litellm

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="[provider_model_name]",  # Replace with actual model name (e.g., "groq/llama3-8b-8192")
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ]
)
```

<!-- Include screenshot only if you have one -->
<Frame>
  <img src="/img/cookbook/[provider_screenshot_name]_trace_cookbook.png" />
</Frame>

<!--
Screenshot should be placed at: apps/opik-documentation/documentation/fern/img/cookbook/[provider_screenshot_name]_trace_cookbook.png
Documentation reference path: /img/cookbook/[provider_screenshot_name]_trace_cookbook.png
-->

## Logging LLM calls within a tracked function

If you are using LiteLLM within a function tracked with the [`@track`](/tracing/log_traces#using-function-decorators) decorator, you will need to pass the `current_span_data` as metadata to the `litellm.completion` call:

```python
from opik import track, opik_context
import litellm

@track
def generate_story(prompt):
    response = litellm.completion(
        model="[provider_model_name]",  # Replace with actual model name
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": opik_context.get_current_span_data(),
            },
        },
    )
    return response.choices[0].message.content


@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    response = litellm.completion(
        model="[provider_model_name_2]",  # Can be same as above or different model
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": opik_context.get_current_span_data(),
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

<!-- Include screenshot only if you have one -->
<Frame>
  <img src="/img/cookbook/[provider_screenshot_name]_trace_decorator_cookbook.png" />
</Frame>

<!--
Screenshot should be placed at: apps/opik-documentation/documentation/fern/img/cookbook/[provider_screenshot_name]_trace_decorator_cookbook.png
Documentation reference path: /img/cookbook/[provider_screenshot_name]_trace_decorator_cookbook.png
-->
