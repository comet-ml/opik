import io
import json
from unittest import mock

import boto3
import botocore.response

import opik
from opik.integrations.bedrock import track_bedrock

MODEL_ID = "anthropic.claude-3-5-sonnet-20241022-v2:0"


def _invoke_model(client, text):
    raw = json.dumps(
        {
            "content": [{"type": "text", "text": text}],
            "usage": {"input_tokens": 1, "output_tokens": 1},
        }
    ).encode()
    response = {
        "body": botocore.response.StreamingBody(io.BytesIO(raw), len(raw)),
        "ResponseMetadata": {"HTTPStatusCode": 200},
    }
    with mock.patch("botocore.client.BaseClient._make_api_call", return_value=response):
        return client.invoke_model(
            modelId=MODEL_ID,
            body=json.dumps({"messages": [{"role": "user", "content": text}]}),
        )


def test_invoke_model__bodies_read_in_reverse_order__each_span_logs_its_own_body(
    fake_backend,
):
    client = track_bedrock(boto3.client("bedrock-runtime", region_name="us-east-1"))

    first = _invoke_model(client, "first")
    second = _invoke_model(client, "second")
    second["body"].read()
    first["body"].read()
    opik.flush_tracker()

    logged = sorted(
        (
            trace_tree.input["body"]["messages"][0]["content"],
            trace_tree.spans[0].output["body"]["content"][0]["text"],
            trace_tree.end_time is not None,
        )
        for trace_tree in fake_backend.trace_trees
    )
    assert logged == [("first", "first", True), ("second", "second", True)]
