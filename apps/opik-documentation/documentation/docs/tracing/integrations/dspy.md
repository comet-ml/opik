---
sidebar_label: DSPy
description: Describes how to track DSPy calls using Opik
---

# DSPy

[DSPy](https://dspy.ai/) is the framework for programming—rather than prompting—language models.

Opik integrates with DSPy to log traces for all DSPy calls.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/dspy.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting started

First, ensure you have both `opik` and `dspy` installed:

```bash
pip install opik dspy
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

## Logging DSPy calls

To log a DSPy pipeline run, you can use the [`OpikCallback`](https://www.comet.com/docs/opik/python-sdk-reference/integrations/dspy/OpikCallback.html). This callback will log each DSPy run to Opik:

```python
import dspy
from opik.integrations.dspy.callback import OpikCallback

project_name = "DSPY"

lm = dspy.LM(
    model="openai/gpt-4o-mini",
)
dspy.configure(lm=lm)


opik_callback = OpikCallback(project_name=project_name)
dspy.settings.configure(
    callbacks=[opik_callback],
)

cot = dspy.ChainOfThought("question -> answer")
cot(question="What is the meaning of life?")
```

Each run will now be logged to the Opik platform:

![DSPy](/img/cookbook/dspy_trace_cookbook.png)
