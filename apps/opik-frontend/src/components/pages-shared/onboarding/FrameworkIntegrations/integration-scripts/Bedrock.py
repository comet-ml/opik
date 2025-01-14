import os

import boto3
from opik.integrations.bedrock import track_bedrock

# INJECT_OPIK_CONFIGURATION

REGION = "us-east-1"
MODEL_ID = "us.meta.llama3-2-3b-instruct-v1:0"

bedrock = boto3.client(
    service_name="bedrock-runtime",
    region_name=REGION,
    # aws_access_key_id=ACCESS_KEY,
    # aws_secret_access_key=SECRET_KEY,
    # aws_session_token=SESSION_TOKEN,
)

bedrock_client = track_bedrock(bedrock)

PROMPT = "Why is it important to use a LLM Monitoring like CometML Opik tool that allows you to log traces and spans when working with LLM Models hosted on AWS Bedrock?"

response = bedrock_client.converse(
    modelId=MODEL_ID,
    messages=[{"role": "user", "content": [{"text": PROMPT}]}],
    inferenceConfig={"temperature": 0.5, "maxTokens": 512, "topP": 0.9},
)
print("Response", response["output"]["message"]["content"][0]["text"])
