---
sidebar_label: OpenAI
description: Describes how to track OpenAI LLM calls using Opik
---

# OpenAI

This guide explains how to integrate Opik with the OpenAI Python SDK. By using the `track_openai` method provided by opik, you can easily track and evaluate your OpenAI API calls within your Opik projects as Opik will automatically log the input prompt, model used, token usage, and response generated.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting started

First, ensure you have both `opik` and `openai` packages installed:

```bash
pip install opik openai
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

## Tracking OpenAI API calls

```python
from opik.integrations.openai import track_openai
from openai import OpenAI

openai_client = OpenAI()
openai_client = track_openai(openai_client)

prompt="Hello, world!"

response = openai_client.chat.completions.create(
    model="gpt-3.5-turbo",
    messages=[
      {"role":"user", "content":prompt}
    ],
    temperature=0.7,
    max_tokens=100,
    top_p=1,
    frequency_penalty=0,
    presence_penalty=0
)

print(response.choices[0].message.content)
```

The `track_openai` will automatically track and log the API call, including the input prompt, model used, and response generated. You can view these logs in your Opik project dashboard.

By following these steps, you can seamlessly integrate Opik with the OpenAI Python SDK and gain valuable insights into your model's performance and usage.

## Supported OpenAI methods

The `track_openai` wrapper supports the following OpenAI methods:

- `openai_client.chat.completions.create()`
- `openai_client.beta.chat.completions.parse()`

If you would like to track another OpenAI method, please let us know by opening an issue on [GitHub](https://github.com/comet-ml/opik/issues).
