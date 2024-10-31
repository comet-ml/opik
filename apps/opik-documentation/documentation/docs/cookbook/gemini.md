# Using Opik with Gemini

Opik integrates with Gemini to provide a simple way to log traces for all Gemini LLM calls. This works for all Gemini models.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) and grab you API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=openai&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik google-generativeai litellm
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
import google.generativeai as genai

if "GEMINI_API_KEY" not in os.environ:
    genai.configure(api_key=getpass.getpass("Enter your Gemini API key: "))
```

## Configure LiteLLM

Add the LiteLLM OpikTracker to log traces and steps to Opik:


```python
import litellm
import os
from litellm.integrations.opik.opik import OpikLogger
from opik import track
from opik.opik_context import get_current_span_data

os.environ["OPIK_PROJECT_NAME"] = "gemini-integration-demo"
opik_logger = OpikLogger()
litellm.callbacks = [opik_logger]
```

## Logging traces

Now each completion will logs a separate trace to LiteLLM:


```python
prompt = """
Write a short two sentence story about Opik.
"""

response = litellm.completion(
    model="gemini/gemini-pro",
    messages=[{"role": "user", "content": prompt}],
)

print(response.choices[0].message.content)
```

The prompt and response messages are automatically logged to Opik and can be viewed in the UI.

![Gemini Cookbook](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/gemini_trace_cookbook.png)

## Using it with the `track` decorator

If you have multiple steps in your LLM pipeline, you can use the `track` decorator to log the traces for each step. If Gemini is called within one of these steps, the LLM call with be associated with that corresponding step:


```python
@track
def generate_story(prompt):
    response = litellm.completion(
        model="gemini/gemini-pro",
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": get_current_span_data(),
            },
        },
    )
    return response.choices[0].message.content


@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    response = litellm.completion(
        model="gemini/gemini-pro",
        messages=[{"role": "user", "content": prompt}],
        metadata={
            "opik": {
                "current_span_data": get_current_span_data(),
            },
        },
    )
    return response.choices[0].message.content


@track
def generate_opik_story():
    topic = generate_topic()
    story = generate_story(topic)
    return story


generate_opik_story()
```

The trace can now be viewed in the UI:

![Gemini Cookbook](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/static/img/cookbook/gemini_trace_decorator_cookbook.png)
