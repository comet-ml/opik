---
sidebar_position: 2
sidebar_label: OpenAI
---

# OpenAI

This guide explains how to integrate Comet Opik with the OpenAI Python SDK. By using the `track_openai` method provided by opik, you can easily track and evaluate your OpenAI API calls within your Comet projects as Comet will automatically log the input prompt, model used, token usage, and response generated.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/openai.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Integration Steps

1. First, ensure you have both `opik` and `openai` packages installed:

```bash
pip install opik openai
```

2. Import the necessary modules and wrap the OpenAI client:

```python
from opik.integrations.openai import track_openai
from openai import OpenAI

openai_client = OpenAI()
openai_client = track_openai(openai_client)

response = openai_client.Completion.create(
    model="gpt-3.5-turbo",
    prompt="Hello, world!",
    temperature=0.7,
    max_tokens=100,
    top_p=1,
    frequency_penalty=0,
    presence_penalty=0
)
```

The `track_openai` will automatically track and log the API call, including the input prompt, model used, and response generated. You can view these logs in your Comet project dashboard.

By following these steps, you can seamlessly integrate Comet Opik with the OpenAI Python SDK and gain valuable insights into your model's performance and usage.
