---
sidebar_label: Predibase
description: Describes how to track Predibase LLM calls using Opik
pytest_codeblocks_skip: true
---

# Using Opik with Predibase

Predibase is a platform for fine-tuning and serving open-source Large Language Models (LLMs). It's built on top of open-source [LoRAX](https://loraexchange.ai/).

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/predibase.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Tracking your LLM calls

Predibase can be used to serve open-source LLMs and is available as a model provider in LangChain. We will leverage the Opik integration with LangChain to track the LLM calls made using Predibase models.

### Getting started

To use the Opik integration with Predibase, you'll need to have both the `opik`, `predibase` and `langchain` packages installed. You can install them using pip:

```bash
pip install --upgrade --quiet opik predibase langchain
```

You can then configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

You will also need to set the `PREDIBASE_API_TOKEN` environment variable to your Predibase API token:

```bash
export PREDIBASE_API_TOKEN=<your-predibase-api-token>
```

### Tracing your Predibase LLM calls

In order to use Predibase through the LangChain interface, we will start by creating a Predibase model. We will then invoke the model with the Opik tracing callback:

```python
import os
from langchain_community.llms import Predibase
from opik.integrations.langchain import OpikTracer

model = Predibase(
    model="mistral-7b",
    predibase_api_key=os.environ.get("PREDIBASE_API_TOKEN"),
)

# Test the model with Opik tracing
response = model.invoke(
    "Can you recommend me a nice dry wine?",
    config={
        "temperature": 0.5,
        "max_new_tokens": 1024,
        "callbacks": [OpikTracer(tags=["predibase", "mistral-7b"])]
    }
)
print(response)
```

:::tip
You can learn more about the Opik integration with LangChain in our [LangChain integration guide](/docs/tracing/integrations/langchain.md) or in the [Predibase cookbook](/docs/cookbook/predibase.md).
:::

The trace will now be available in the Opik UI for further analysis.

![predibase](/img/tracing/predibase_opik_trace.png)

## Tracking your fine-tuning training runs

If you are using Predibase to fine-tune an LLM, we recommend using Predibase's integration with Comet's Experiment Management functionality. You can learn more about how to set this up in the [Comet integration guide](https://docs.predibase.com/user-guide/integrations/comet) in the Predibase documentation. If you are already using an Experiment Tracking platform, worth checking if it has an integration with Predibase.
