# Using Opik with AWS Bedrock

Opik integrates with AWS Bedrock to provide a simple way to log traces for all Bedrock LLM calls. This works for all supported models, including if you are using the streaming API.


## Creating an account on Comet.com

[Comet](https://www.comet.com/site?from=llm&utm_source=opik&utm_medium=colab&utm_content=bedrock&utm_campaign=opik) provides a hosted version of the Opik platform, [simply create an account](https://www.comet.com/signup?from=llm&utm_source=opik&utm_medium=colab&utm_content=bedrock&utm_campaign=opik) and grab your API Key.

> You can also run the Opik platform locally, see the [installation guide](https://www.comet.com/docs/opik/self-host/overview/?from=llm&utm_source=opik&utm_medium=colab&utm_content=bedrock&utm_campaign=opik) for more information.


```python
%pip install --upgrade opik boto3
```


```python
import opik

opik.configure(use_local=False)
```

## Preparing our environment

First, we will set up our bedrock client. Uncomment the following lines to pass AWS Credentials manually or [checkout other ways of passing credentials to Boto3](https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html). You will also need to request access to the model in the UI before being able to generate text, here we are gonna use the Llama 3.2 model, you can request access to it in [this page for the us-east1](https://us-east-1.console.aws.amazon.com/bedrock/home?region=us-east-1#/providers?model=meta.llama3-2-3b-instruct-v1:0) region.


```python
import boto3

REGION = "us-east-1"

MODEL_ID = "us.meta.llama3-2-3b-instruct-v1:0"

bedrock = boto3.client(
    service_name="bedrock-runtime",
    region_name=REGION,
    # aws_access_key_id=ACCESS_KEY,
    # aws_secret_access_key=SECRET_KEY,
    # aws_session_token=SESSION_TOKEN,
)
```

## Logging traces

In order to log traces to Opik, we need to wrap our Bedrock calls with the `track_bedrock` function:


```python
import os

from opik.integrations.bedrock import track_bedrock

bedrock_client = track_bedrock(bedrock, project_name="bedrock-integration-demo")
```


```python
PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with LLM Models hosted on AWS Bedrock?"

response = bedrock_client.converse(
    modelId=MODEL_ID,
    messages=[{"role": "user", "content": [{"text": PROMPT}]}],
    inferenceConfig={"temperature": 0.5, "maxTokens": 512, "topP": 0.9},
)
print("Response", response["output"]["message"]["content"][0]["text"])
```

The prompt and response messages are automatically logged to Opik and can be viewed in the UI.

![Bedrock Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/bedrock_trace_cookbook.png)

# Logging traces with streaming


```python
def stream_conversation(
    bedrock_client,
    model_id,
    messages,
    system_prompts,
    inference_config,
):
    """
    Sends messages to a model and streams the response.
    Args:
        bedrock_client: The Boto3 Bedrock runtime client.
        model_id (str): The model ID to use.
        messages (JSON) : The messages to send.
        system_prompts (JSON) : The system prompts to send.
        inference_config (JSON) : The inference configuration to use.
        additional_model_fields (JSON) : Additional model fields to use.

    Returns:
        Nothing.

    """

    response = bedrock_client.converse_stream(
        modelId=model_id,
        messages=messages,
        system=system_prompts,
        inferenceConfig=inference_config,
    )

    stream = response.get("stream")
    if stream:
        for event in stream:
            if "messageStart" in event:
                print(f"\nRole: {event['messageStart']['role']}")

            if "contentBlockDelta" in event:
                print(event["contentBlockDelta"]["delta"]["text"], end="")

            if "messageStop" in event:
                print(f"\nStop reason: {event['messageStop']['stopReason']}")

            if "metadata" in event:
                metadata = event["metadata"]
                if "usage" in metadata:
                    print("\nToken usage")
                    print(f"Input tokens: {metadata['usage']['inputTokens']}")
                    print(f":Output tokens: {metadata['usage']['outputTokens']}")
                    print(f":Total tokens: {metadata['usage']['totalTokens']}")
                if "metrics" in event["metadata"]:
                    print(f"Latency: {metadata['metrics']['latencyMs']} milliseconds")


system_prompt = """You are an app that creates playlists for a radio station
  that plays rock and pop music. Only return song names and the artist."""

# Message to send to the model.
input_text = "Create a list of 3 pop songs."


message = {"role": "user", "content": [{"text": input_text}]}
messages = [message]

# System prompts.
system_prompts = [{"text": system_prompt}]

# inference parameters to use.
temperature = 0.5
top_p = 0.9
# Base inference parameters.
inference_config = {"temperature": temperature, "topP": 0.9}


stream_conversation(
    bedrock_client,
    MODEL_ID,
    messages,
    system_prompts,
    inference_config,
)
```

![Bedrock Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/bedrock_trace_streaming_cookbook.png)

## Using it with the `track` decorator

If you have multiple steps in your LLM pipeline, you can use the `track` decorator to log the traces for each step. If Bedrock is called within one of these steps, the LLM call with be associated with that corresponding step:


```python
from opik import track
from opik.integrations.bedrock import track_bedrock

bedrock = boto3.client(
    service_name="bedrock-runtime",
    region_name=REGION,
    # aws_access_key_id=ACCESS_KEY,
    # aws_secret_access_key=SECRET_KEY,
    # aws_session_token=SESSION_TOKEN,
)

os.environ["OPIK_PROJECT_NAME"] = "bedrock-integration-demo"
bedrock_client = track_bedrock(bedrock)


@track
def generate_story(prompt):
    res = bedrock_client.converse(
        modelId=MODEL_ID, messages=[{"role": "user", "content": [{"text": prompt}]}]
    )
    return res["output"]["message"]["content"][0]["text"]


@track
def generate_topic():
    prompt = "Generate a topic for a story about Opik."
    res = bedrock_client.converse(
        modelId=MODEL_ID, messages=[{"role": "user", "content": [{"text": prompt}]}]
    )
    return res["output"]["message"]["content"][0]["text"]


@track
def generate_opik_story():
    topic = generate_topic()
    story = generate_story(topic)
    return story


generate_opik_story()
```

The trace can now be viewed in the UI:

![Bedrock Integration](https://raw.githubusercontent.com/comet-ml/opik/main/apps/opik-documentation/documentation/fern/img/cookbook/bedrock_trace_decorator_cookbook.png)


