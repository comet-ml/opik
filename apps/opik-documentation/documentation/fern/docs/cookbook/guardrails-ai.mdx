# Using Opik with Guardrails AI

[Guardrails AI](https://github.com/guardrails-ai/guardrails) is a framework for validating the inputs and outputs 

For this guide we will use a simple example that logs guardrails validation steps as traces to Opik, providing them with the validation result tags.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik guardrails-ai
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

In order to use Guardrails AI, we will configure the OpenAI API Key, if you are using any other providers you can replace this with the required API key:


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

We will also need to install the guardrails check for politeness from the Guardrails Hub


```python
!guardrails hub install hub://guardrails/politeness_check
```

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

![Guardrails AI Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/guardrails_ai_traces_cookbook.png)


