# Using Opik with Anthropic

Opik integrates with Anthropic to provide a simple way to log traces for all Anthropic LLM calls. This works for all supported models, including if you are using the streaming API.


## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=anthropic&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=anthropic&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=anthropic&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik anthropic
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will set up our anthropic client. You can [find or create your Anthropic API Key in this page page](https://console.anthropic.com/settings/keys) and paste it below:


```python
import os
import getpass
import anthropic

if "ANTHROPIC_API_KEY" not in os.environ:
    os.environ["ANTHROPIC_API_KEY"] = getpass.getpass("Enter your Anthropic API key: ")
```

## Logging traces

In order to log traces to Opik, we need to wrap our Anthropic calls with the `track_anthropic` function:


```python
import os

from opik.integrations.anthropic import track_anthropic

anthropic_client = anthropic.Anthropic()
anthropic_client = track_anthropic(
    anthropic_client, project_name="anthropic-integration-demo"
)
```


```python
PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with Anthropic LLM Models?"

response = anthropic_client.messages.create(
    model="claude-3-5-sonnet-20241022",
    max_tokens=1024,
    messages=[{"role": "user", "content": PROMPT}],
)
print("Response", response.content[0].text)
```

The prompt and response messages are automatically logged to Opik and can be viewed in the UI.

![Anthropic Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/anthropic_trace_cookbook.png)

## Using it with the `track` decorator

If you have multiple steps in your LLM pipeline, you can use the `track` decorator to log the traces for each step. If Anthropic is called within one of these steps, the LLM call with be associated with that corresponding step:


```python
import anthropic

from opik import track
from opik.integrations.anthropic import track_anthropic

os.environ["OPIK_PROJECT_NAME"] = "anthropic-integration-demo"

anthropic_client = anthropic.Anthropic()
anthropic_client = track_anthropic(anthropic_client)


@track
def generate_story(prompt):
    res = anthropic_client.messages.create(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1024,
        messages=[{"role": "user", "content": prompt}],
    )
    return res.content[0].text


@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    res = anthropic_client.messages.create(
        model="claude-3-5-sonnet-20241022",
        max_tokens=1024,
        messages=[{"role": "user", "content": prompt}],
    )
    return res.content[0].text


@track
def generate_opik_story():
    topic = generate_topic()
    story = generate_story(topic)
    return story


generate_opik_story()
```

The trace can now be viewed in the UI:

![Anthropic Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/anthropic_trace_decorator_cookbook.png)


