---
sidebar_position: 2
sidebar_label: Quickstart
---

# Quickstart

This guide helps you integrate the Comet LLM Evaluation platform with your existing LLM application.

## Set up

Getting started is as simple as creating an [account on Comet](./) or [self-hosting the platform](./).

Once your account is created, you can start logging traces by installing and configuring the Python SDK:

```bash
pip install opik

export COMET_API_KEY=<...>
```

:::note
You do not need to set the `COMET_API_KEY` environment variable if you are self-hosting the platform. Instead you will need to set:

```bash
EXPORT COMET_URL_OVERRIDE="http://localhost:5173/api"
```
:::

## Integrating with your LLM application

You can start logging traces to Comet by simply adding the `opik.track` decorator to your LLM application:

```python
from opik import track

@track
def your_llm_application(input):
    # Your existing LLM application logic here
    output = "..."
    return output
```

To learn more about the `track` decorator, see the [`track` documentation](./track).

