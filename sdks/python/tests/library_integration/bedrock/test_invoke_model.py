import json
import boto3
import pytest

import opik
from opik.integrations.bedrock import track_bedrock
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)
from .constants import (
    EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT,
)

# Test models for each subprovider (using inference profiles for accessibility)
ANTHROPIC_MODEL = "us.anthropic.claude-sonnet-4-20250514-v1:0"  # Claude format (latest)
AMAZON_MODEL = "us.amazon.nova-pro-v1:0"  # Nova format
META_MODEL = "us.meta.llama3-1-8b-instruct-v1:0"  # Llama format
MISTRAL_MODEL = "us.mistral.pixtral-large-2502-v1:0"  # Mistral format

pytestmark = pytest.mark.usefixtures("ensure_aws_bedrock_configured")


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, "Default Project"),
        ("bedrock-integration-test", "bedrock-integration-test"),
    ],
)
def test_bedrock_invoke_model__anthropic___happyflow(
    fake_backend, project_name, expected_project_name
):
    """Test basic invoke_model functionality with Bedrock client."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client, project_name=project_name)

    # Prepare request body for Claude
    request_body = {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": 50,
        "temperature": 0.1,
        "messages": [{"role": "user", "content": "Hello, how are you?"}],
    }

    response = tracked_client.invoke_model(
        modelId=ANTHROPIC_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )
    response_body = json.loads(response["body"].read())
    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model",
        input={"body": request_body, "modelId": ANTHROPIC_MODEL},
        output={"body": response_body},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model",
                type="llm",
                input={"body": request_body, "modelId": ANTHROPIC_MODEL},
                output={"body": response_body},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=ANTHROPIC_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(expected_trace, trace_tree)


def test_bedrock_invoke_model__create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    """Test that errors are properly logged as error spans."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client)

    request_body = {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": 50,
        "messages": [{"role": "user", "content": "Test message"}],
    }

    # Use an invalid model to trigger an error
    with pytest.raises(Exception):
        tracked_client.invoke_model(
            modelId="invalid-model-id",
            body=json.dumps(request_body),
            contentType="application/json",
            accept="application/json",
        )

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model",
        input={"body": request_body, "modelId": "invalid-model-id"},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        error_info=ANY_DICT.containing(
            {
                "exception_type": ANY_STRING,
                "message": ANY_STRING,
                "traceback": ANY_STRING,
            }
        ),
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model",
                type="llm",
                input={"body": request_body, "modelId": "invalid-model-id"},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model="invalid-model-id",
                provider="bedrock",
                error_info=ANY_DICT.containing(
                    {
                        "exception_type": ANY_STRING,
                        "message": ANY_STRING,
                        "traceback": ANY_STRING,
                    }
                ),
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(expected_trace, trace_tree)


def test_bedrock_invoke_model__anthropic___invoke_model_call_made_in_another_tracked_function__bedrock_span_attached_to_existing_trace(
    fake_backend,
):
    """Test that invoke_model calls within tracked functions create proper nesting."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client)

    @opik.track()
    def ask_bedrock_question(question: str) -> str:
        request_body = {
            "anthropic_version": "bedrock-2023-05-31",
            "max_tokens": 50,
            "messages": [{"role": "user", "content": question}],
        }

        response = tracked_client.invoke_model(
            modelId=ANTHROPIC_MODEL,
            body=json.dumps(request_body),
            contentType="application/json",
            accept="application/json",
        )

        response_body = json.loads(response["body"].read())
        return response_body["content"][0]["text"]

    result = ask_bedrock_question("What is 2+2?")
    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="ask_bedrock_question",
        input={"question": "What is 2+2?"},
        output={"output": result},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="ask_bedrock_question",
                input={"question": "What is 2+2?"},
                output={"output": result},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                last_updated_at=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="bedrock_invoke_model",
                        type="llm",
                        input={
                            "body": {
                                "anthropic_version": "bedrock-2023-05-31",
                                "max_tokens": 50,
                                "messages": [
                                    {"role": "user", "content": "What is 2+2?"}
                                ],
                            },
                            "modelId": ANTHROPIC_MODEL,
                        },
                        output={"body": ANY_DICT},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        tags=["bedrock", "invoke_model"],
                        metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                        last_updated_at=ANY_BUT_NONE,
                        model=ANTHROPIC_MODEL,
                        usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                        provider="bedrock",
                        spans=[],
                    )
                ],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(expected_trace, trace_tree)


# Test cases for all subproviders


def test_bedrock_invoke_model__anthropic___streaming__happyflow(fake_backend):
    """Test Anthropic Claude streaming invoke_model_with_response_stream."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "anthropic_version": "bedrock-2023-05-31",
        "max_tokens": 20,
        "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}],
    }

    response = tracked_client.invoke_model_with_response_stream(
        modelId=ANTHROPIC_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )

    # Consume the stream
    for _ in response["body"]:
        pass

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model_stream",
        input={"body": request_body, "modelId": ANTHROPIC_MODEL},
        output={"body": ANY_DICT},  # Contains native Claude format
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model_stream",
                type="llm",
                input={"body": request_body, "modelId": ANTHROPIC_MODEL},
                output={"body": ANY_DICT},  # Contains native Claude format
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=ANTHROPIC_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_bedrock_invoke_model__amazon_nova___non_streaming__happyflow(fake_backend):
    """Test Amazon Nova non-streaming invoke_model."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "messages": [{"role": "user", "content": [{"text": "Hello"}]}],
        "inferenceConfig": {"max_new_tokens": 20},
    }

    response = tracked_client.invoke_model(
        modelId=AMAZON_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )
    response_body = json.loads(response["body"].read())
    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model",
        input={"body": request_body, "modelId": AMAZON_MODEL},
        output={"body": response_body},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model",
                type="llm",
                input={"body": request_body, "modelId": AMAZON_MODEL},
                output={"body": response_body},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=AMAZON_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_bedrock_invoke_model__amazon_nova___streaming__happyflow(fake_backend):
    """Test Amazon Nova streaming invoke_model_with_response_stream."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "messages": [{"role": "user", "content": [{"text": "Hello"}]}],
        "inferenceConfig": {"max_new_tokens": 20},
    }

    response = tracked_client.invoke_model_with_response_stream(
        modelId=AMAZON_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )

    # Consume the stream
    for _ in response["body"]:
        pass

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model_stream",
        input={"body": request_body, "modelId": AMAZON_MODEL},
        output={"body": ANY_DICT},  # Contains native Nova format
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model_stream",
                type="llm",
                input={"body": request_body, "modelId": AMAZON_MODEL},
                output={"body": ANY_DICT},  # Contains native Nova format
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=AMAZON_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_bedrock_invoke_model__meta_llama___non_streaming__happyflow(fake_backend):
    """Test Meta Llama non-streaming invoke_model."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "prompt": "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
        "max_gen_len": 20,
    }

    response = tracked_client.invoke_model(
        modelId=META_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )
    response_body = json.loads(response["body"].read())
    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model",
        input={"body": request_body, "modelId": META_MODEL},
        output={"body": response_body},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model",
                type="llm",
                input={"body": request_body, "modelId": META_MODEL},
                output={"body": response_body},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=META_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_bedrock_invoke_model__meta_llama___streaming__happyflow(fake_backend):
    """Test Meta Llama streaming invoke_model_with_response_stream."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "prompt": "<|begin_of_text|><|start_header_id|>user<|end_header_id|>\n\nHello<|eot_id|><|start_header_id|>assistant<|end_header_id|>\n\n",
        "max_gen_len": 20,
    }

    response = tracked_client.invoke_model_with_response_stream(
        modelId=META_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )

    # Consume the stream
    for _ in response["body"]:
        pass

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model_stream",
        input={"body": request_body, "modelId": META_MODEL},
        output={"body": ANY_DICT},  # Contains native Llama format
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model_stream",
                type="llm",
                input={"body": request_body, "modelId": META_MODEL},
                output={"body": ANY_DICT},  # Contains native Llama format
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=META_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_bedrock_invoke_model__mistral___non_streaming__happyflow(fake_backend):
    """Test Mistral/Pixtral non-streaming invoke_model."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}],
        "max_tokens": 20,
    }

    response = tracked_client.invoke_model(
        modelId=MISTRAL_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )
    response_body = json.loads(response["body"].read())
    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model",
        input={"body": request_body, "modelId": MISTRAL_MODEL},
        output={"body": response_body},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model",
                type="llm",
                input={"body": request_body, "modelId": MISTRAL_MODEL},
                output={"body": response_body},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=MISTRAL_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])


def test_bedrock_invoke_model__mistral___streaming__happyflow(fake_backend):
    """Test Mistral/Pixtral streaming invoke_model_with_response_stream."""
    client = boto3.client("bedrock-runtime", region_name="us-east-2")
    tracked_client = track_bedrock(client)

    request_body = {
        "messages": [{"role": "user", "content": [{"type": "text", "text": "Hello"}]}],
        "max_tokens": 20,
    }

    response = tracked_client.invoke_model_with_response_stream(
        modelId=MISTRAL_MODEL,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )

    # Consume the stream
    for _ in response["body"]:
        pass

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model_stream",
        input={"body": request_body, "modelId": MISTRAL_MODEL},
        output={"body": ANY_DICT},  # Contains native Mistral format
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock", "invoke_model"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_invoke_model_stream",
                type="llm",
                input={"body": request_body, "modelId": MISTRAL_MODEL},
                output={"body": ANY_DICT},  # Contains native Mistral format
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=MISTRAL_MODEL,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    assert_equal(expected_trace, fake_backend.trace_trees[0])
