---
sidebar_label: Gemini - Google AI Studio
description: Describes how to track Gemini LLM calls using Opik
pytest_codeblocks_skip: true
---

# Gemini - Google AI Studio

[Gemini](https://aistudio.google.com/welcome) is a family of multimodal large language models developed by Google DeepMind.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/gemini.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

### Configuring Opik

To start tracking your Gemini LLM calls, you can use our [LiteLLM integration](/tracing/integrations/litellm.md). You'll need to have both the `opik`, `litellm` and `google-generativeai` packages installed. You can install them using pip:

```bash
pip install opik litellm google-generativeai
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

:::info

If you’re unable to use our LiteLLM integration with Gemini, please [open an issue](https://github.com/comet-ml/opik/issues/new/choose)

:::

### Configuring Gemini

In order to configure Gemini, you will need to have:

- Your Gemini API Key: See the [following documentation page](https://ai.google.dev/gemini-api/docs/api-key) how to retrieve it.

Once you have these, you can set them as environment variables:

```python pytest_codeblocks_skip=true
import os

os.environ["GEMINI_API_KEY"] = "" # Your Google AI Studio Gemini API Key
```

## Logging LLM calls

In order to log the LLM calls to Opik, you will need to create the OpikLogger callback. Once the OpikLogger callback is created and added to LiteLLM, you can make calls to LiteLLM as you normally would:

```python
from litellm.integrations.opik.opik import OpikLogger
import litellm

opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]

response = litellm.completion(
    model="gemini/gemini-pro",
    messages=[
        {"role": "user", "content": "Why is tracking and evaluation of LLMs important?"}
    ]
)
```

![Gemini Integration](/img/cookbook/gemini_trace_cookbook.png)

## Logging LLM calls within a tracked function

If you are using LiteLLM within a function tracked with the [`@track`](/tracing/log_traces.mdx#using-function-decorators) decorator, you will need to pass the `current_span_data` as metadata to the `litellm.completion` call:

```python
from opik import track, opik_context
import litellm

@track
def generate_story(prompt):
    response = litellm.completion(
        model="gemini/gemini-pro",
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
        model="gemini/gemini-pro",
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

![Gemini Integration](/img/cookbook/gemini_trace_decorator_cookbook.png)
