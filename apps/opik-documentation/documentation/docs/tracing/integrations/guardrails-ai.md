---
sidebar_label: Guardrails AI
description: Cookbook that showcases Opik's integration with the Guardrails AI Python SDK
---

# Guardrails AI

[Guardrails AI](https://github.com/guardrails-ai/guardrails) is a framework for validating the inputs and outputs

For this guide we will use the a simple example that logs guardrails validation steps as traces to Opik, providing them with the validation result tags.

## Preparing our environment

In order to use Guardrails AI, we will configure the OpenAI API Key, if you are using any other providers you can replace this with the required API key:

```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

We will also need to install the guardrails check for politeness from the Guardrails Hub

`guardrails hub install hub://guardrails/politeness_check`

## Logging validation traces

In order to log traces to Opik, you will need to call the track the Guard object with `track_guardrails` function.

```python
from guardrails import Guard, OnFailAction
from guardrails.hub import PolitenessCheck

from opik.integrations.guardrails import track_guardrails

politeness_check = PolitenessCheck(
    llm_callable="gpt-3.5-turbo", on_fail=OnFailAction.NOOP
)

guard: Guard = Guard().use_many(politeness_check)
guard = track_guardrails(guard, project_name="guardrails-integration-example")

guard.validate(
    "Would you be so kind to pass me a cup of tea?",
)
guard.validate(
    "Shut your mouth up and give me the tea.",
);
```

Every validation will now be logged to Opik as a trace

The trace will now be viewable in the Opik platform:

![Guardrails AI Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/guardrails_ai_traces_cookbook.png)
