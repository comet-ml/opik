---
sidebar_label: Bedrock
description: Describes how to track Bedrock LLM calls using Opik
pytest_codeblocks_skip: true
---

# AWS Bedrock

[AWS Bedrock](https://aws.amazon.com/bedrock/) is a fully managed service that provides access to high-performing foundation models (FMs) from leading AI companies like AI21 Labs, Anthropic, Cohere, Meta, Mistral AI, Stability AI, and Amazon through a single API.

This guide explains how to integrate Opik with the Bedrock Python SDK. By using the `track_bedrock` method provided by opik, you can easily track and evaluate your Bedrock API calls within your Opik projects as Opik will automatically log the input prompt, model used, token usage, and response generated.

<div style="display: flex; align-items: center; flex-wrap: wrap; margin: 20px 0;">
  <span style="margin-right: 10px;">You can check out the Colab Notebook if you'd like to jump straight to the code:</span>
  <a href="https://colab.research.google.com/github/comet-ml/opik/blob/main/apps/opik-documentation/documentation/docs/cookbook/bedrock.ipynb" target="_blank" rel="noopener noreferrer">
    <img src="https://colab.research.google.com/assets/colab-badge.svg" alt="Open In Colab" style="vertical-align: middle;"/>
  </a>
</div>

## Getting Started

### Configuring Opik

To start tracking your Bedrock LLM calls, you'll need to have both the `opik` and `boto3`. You can install them using pip:

```bash
pip install opik boto3
```

In addition, you can configure Opik using the `opik configure` command which will prompt you for the correct local server address or if you are using the Cloud platform your API key:

```bash
opik configure
```

### Configuring Bedrock

In order to configure Bedrock, you will need to have:

- Your AWS Credentials configured for boto, see the [following documentation page for how to set them up](https://boto3.amazonaws.com/v1/documentation/api/latest/guide/credentials.html).
- Access to the model you are planning to use, see the [following documentation page how to do so](https://docs.aws.amazon.com/bedrock/latest/userguide/model-access-modify.html).

Once you have these, you can set create your boto3 client:

```python
import boto3

REGION = "us-east-1"

bedrock = boto3.client(
    service_name="bedrock-runtime",
    region_name=REGION,
    # aws_access_key_id=ACCESS_KEY,
    # aws_secret_access_key=SECRET_KEY,
    # aws_session_token=SESSION_TOKEN,
)
```

## Logging LLM calls

In order to log the LLM calls to Opik, you will need to create the wrap the boto3 client with `track_bedrock`. When making calls with that wrapped client, all calls will be logged to Opik:

```python
from opik.integrations.bedrock import track_bedrock

bedrock_client = track_bedrock(bedrock, project_name="bedrock-integration-demo")

PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with LLM Models hosted on AWS Bedrock?"

response = bedrock_client.converse(
    modelId=MODEL_ID,
    messages=[{"role": "user", "content": [{"text": PROMPT}]}],
    inferenceConfig={"temperature": 0.5, "maxTokens": 512, "topP": 0.9},
)
print("Response", response["output"]["message"]["content"][0]["text"])
```

![Bedrock Integration](/img/cookbook/bedrock_trace_cookbook.png)
