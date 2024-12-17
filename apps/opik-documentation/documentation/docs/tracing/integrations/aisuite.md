---
sidebar_label: aisuite
---

# aisuite

This guide explains how to integrate Opik with the aisuite Python SDK. By using the `track_aisuite` method provided by opik, you can easily track and evaluate your aisuite API calls within your Opik projects as Opik will automatically log the input prompt, model used, token usage, and response generated.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/aisuite.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting started

First, ensure you have both `opik` and `aisuite` packages installed:

```bash
pip install opik "aisuite[openai]"
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platfrom your API key:

```bash
opik configure
```

## Tracking aisuite API calls

```python
from opik.integrations.aisuite import track_aisuite
import aisuite as ai

client = track_aisuite(ai.Client(), project_name="aisuite-integration-demo")

messages = [
    {"role": "user", "content": "Write a short two sentence story about Opik."},
]

response = client.chat.completions.create(
    model="openai:gpt-4o",
    messages=messages,
    temperature=0.75
)
print(response.choices[0].message.content)
```

The `track_aisuite` will automatically track and log the API call, including the input prompt, model used, and response generated. You can view these logs in your Opik project dashboard.

By following these steps, you can seamlessly integrate Opik with the aisuite Python SDK and gain valuable insights into your model's performance and usage.

## Supported aisuite methods

The `track_aisuite` wrapper supports the following aisuite methods:

- `aisuite.Client.chat.completions.create()`

If you would like to track another aisuite method, please let us know by opening an issue on [GitHub](https://github.com/comet-ml/opik/issues).
