# Using Opik with OpenAI

Opik integrates with OpenAI to provide a simple way to log traces for all OpenAI LLM calls. This works for all OpenAI models, including if you are using the streaming API.


## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) for more information.


```python
%pip install --upgrade --quiet opik openai
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will set up our OpenAI API keys.


```python
import os
import getpass

if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")
```

## Logging traces

In order to log traces to Opik, we need to wrap our OpenAI calls with the `track_openai` function:


```python
from opik.integrations.openai import track_openai
from openai import OpenAI

os.environ["OPIK_PROJECT_NAME"] = "openai-integration-demo"
client = OpenAI()

openai_client = track_openai(client)

prompt = """
Write a short two sentence story about Opik.
"""

completion = openai_client.chat.completions.create(
  model="gpt-3.5-turbo",
  messages=[
    {"role": "user", "content": prompt}
  ]
)

print(completion.choices[0].message.content)
```

The prompt and response messages are automatically logged to Opik and can be viewed in the UI.

![OpenAI Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/openai_trace_cookbook.png)

## Using it with the `track` decorator

If you have multiple steps in your LLM pipeline, you can use the `track` decorator to log the traces for each step. If OpenAI is called within one of these steps, the LLM call with be associated with that corresponding step:


```python
from opik import track
from opik.integrations.openai import track_openai
from openai import OpenAI

os.environ["OPIK_PROJECT_NAME"] = "openai-integration-demo"

client = OpenAI()
openai_client = track_openai(client)

@track
def generate_story(prompt):
    res = openai_client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[
            {"role": "user", "content": prompt}
        ]
    )
    return res.choices[0].message.content

@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    res = openai_client.chat.completions.create(
        model="gpt-3.5-turbo",
        messages=[
            {"role": "user", "content": prompt}
        ]
    )
    return res.choices[0].message.content

@track
def generate_opik_story():
    topic = generate_topic()
    story = generate_story(topic)
    return story

generate_opik_story()

```

The trace can now be viewed in the UI:

![OpenAI Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/openai_trace_decorator_cookbook.png)


