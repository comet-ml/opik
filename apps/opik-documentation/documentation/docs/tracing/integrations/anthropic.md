---
sidebar_label: Anthropic
description: Describes how to track Anthropic LLM calls using Opik
---

# Anthropic

[Anthropic](https://www.anthropic.com/) is an AI safety and research company that's working to build reliable, interpretable, and steerable AI systems.

This guide explains how to integrate Opik with the Anthropic Python SDK. By using the `track_anthropic` method provided by opik, you can easily track and evaluate your Anthropic API calls within your Opik projects as Opik will automatically log the input prompt, model used, token usage, and response generated.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/anthropic.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

### Configuring Opik

To start tracking your Anthropic LLM calls, you'll need to have both the `opik` and `anthropic`. You can install them using pip:

```bash
pip install opik anthropic
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

### Configuring Anthropic

In order to configure Anthropic, you will need to have your Anthropic API Key set, see this [section how to pass your Anthropic API Key](https://github.com/anthropics/anthropic-sdk-python?tab=readme-ov-file#usage).

Once you have it, you can set it as an environment variable:

```bash pytest_codeblocks_skip=true
export ANTHROPIC_API_KEY="YOUR_API_KEY"
```

## Logging LLM calls

In order to log the LLM calls to Opik, you will need to create the wrap the anthropic client with `track_anthropic`. When making calls with that wrapped client, all calls will be logged to Opik:

```python
import anthropic
from opik.integrations.anthropic import track_anthropic

anthropic_client = anthropic.Anthropic()
anthropic_client = track_anthropic(anthropic_client, project_name="anthropic-integration-demo")

PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with Anthropic LLM Models?"

response = anthropic_client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[
        {"role": "user", "content": PROMPT}
    ]
)
print("Response", response.content[0].text)
```

![Anthropic Integration](/img/cookbook/anthropic_trace_cookbook.png)
