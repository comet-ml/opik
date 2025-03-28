# Using Opik with Instructor

[Instructor](https://github.com/instructor-ai/instructor) is a Python library for working with structured outputs for LLMs built on top of Pydantic. It provides a simple way to manage schema validations, retries and streaming responses.

## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=haystack&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=haystack&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=haystack&utm_campaign=opik) for more information.


```python
%pip install --upgrade --quiet opik instructor anthropic google-generativeai google-genai
```


```python
import os
import getpass
```

## Opik Config

Configure your development environment (If you click the key icon on the left side, you can set API keys that are reusable across notebooks.)


```python
import opik

opik.configure(use_local=False)

os.environ["OPIK_PROJECT_NAME"] = "opik-cookbook-instructor"
```

For this demo we are going to use an OpenAI, Anthropic and Gemini, so we will need to configure our API keys:


```python
if "OPENAI_API_KEY" not in os.environ:
    os.environ["OPENAI_API_KEY"] = getpass.getpass("Enter your OpenAI API key: ")

if "ANTHROPIC_API_KEY" not in os.environ:
    os.environ["ANTHROPIC_API_KEY"] = getpass.getpass("Enter your Anthropic API key: ")

if "GOOGLE_API_KEY" not in os.environ:
    os.environ["GOOGLE_API_KEY"] = getpass.getpass("Enter your Google API key: ")
```

## Using Opik with Instructor library

In order to log traces from Instructor into Opik, we are going to patch the `instructor` library. This will log each LLM call to the Opik platform.

For all the integrations, we will first add tracking to the LLM client and then pass it to the Instructor library:


```python
from opik.integrations.openai import track_openai
import instructor
from pydantic import BaseModel
from openai import OpenAI


# We will first create the OpenAI client and add the `track_openai`
# method to log data to Opik
openai_client = track_openai(OpenAI())

# Patch the OpenAI client for Instructor
client = instructor.from_openai(openai_client)


# Define your desired output structure
class UserInfo(BaseModel):
    name: str
    age: int


user_info = client.chat.completions.create(
    model="gpt-4o-mini",
    response_model=UserInfo,
    messages=[{"role": "user", "content": "John Doe is 30 years old."}],
)

print(user_info)
```

Thanks to the `track_openai` method, all the calls made to OpenAI will be logged to the Opik platform. This approach also works well if you are also using the `opik.track` decorator as it will automatically log the LLM call made with Instructor to the relevant trace.

![Trace screenshot](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/instructor_cookbook.png)

## Integrating with other LLM providers

The instructor library supports many LLM providers beyond OpenAI, including: Anthropic, AWS Bedrock, Gemini, etc. Opik supports the majority of these providers aswell.

Here are two additional code snippets needed for the integration.

### Anthropic


```python
from opik.integrations.anthropic import track_anthropic
import instructor
from anthropic import Anthropic

# Add Opik tracking
anthropic_client = track_anthropic(Anthropic())

# Patch the Anthropic client for Instructor
client = instructor.from_anthropic(
    anthropic_client, mode=instructor.Mode.ANTHROPIC_JSON
)

user_info = client.chat.completions.create(
    model="claude-3-5-sonnet-20241022",
    response_model=UserInfo,
    messages=[{"role": "user", "content": "John Doe is 30 years old."}],
    max_tokens=1000,
)

print(user_info)
```

### Gemini


```python
from opik.integrations.genai import track_genai
import instructor
from google import genai

# Add Opik tracking
gemini_client = track_genai(genai.Client())

# Patch the GenAI client for Instructor
client = instructor.from_genai(
    gemini_client, mode=instructor.Mode.GENAI_STRUCTURED_OUTPUTS
)

user_info = client.chat.completions.create(
    model="gemini-2.0-flash-001",
    response_model=UserInfo,
    messages=[{"role": "user", "content": "John Doe is 30 years old."}],
)

print(user_info)
```
