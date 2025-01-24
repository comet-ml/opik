import boto3
from opik.integrations.bedrock import track_bedrock

# INJECT_OPIK_CONFIGURATION

bedrock = boto3.client(
    service_name="bedrock-runtime",
    region_name="us-east-1",
    # aws_access_key_id=ACCESS_KEY,
    # aws_secret_access_key=SECRET_KEY,
    # aws_session_token=SESSION_TOKEN,
)
bedrock_client = track_bedrock(bedrock)
response = bedrock_client.converse(
    modelId="us.meta.llama3-2-3b-instruct-v1:0",
    messages=[
        {"role": "user", "content": [{"text": "Write a haiku about AI engineering."}]}
    ],
)
print("Response", response["output"]["message"]["content"][0]["text"])
