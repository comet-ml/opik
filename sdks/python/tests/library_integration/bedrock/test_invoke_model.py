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
    BEDROCK_MODEL_FOR_TESTS,
    EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT,
)

pytestmark = pytest.mark.usefixtures("ensure_aws_bedrock_configured")


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, "Default Project"),
        ("bedrock-integration-test", "bedrock-integration-test"),
    ],
)
def test_bedrock_invoke_model__happyflow(
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
        modelId=BEDROCK_MODEL_FOR_TESTS,
        body=json.dumps(request_body),
        contentType="application/json",
        accept="application/json",
    )
    response_body = json.loads(response["body"].read())
    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_invoke_model",
        input={"body": request_body, "modelId": BEDROCK_MODEL_FOR_TESTS},
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
                input={"body": request_body, "modelId": BEDROCK_MODEL_FOR_TESTS},
                output={"body": response_body},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock", "invoke_model"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=BEDROCK_MODEL_FOR_TESTS,
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


def test_bedrock_invoke_model__invoke_model_call_made_in_another_tracked_function__bedrock_span_attached_to_existing_trace(
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
            modelId=BEDROCK_MODEL_FOR_TESTS,
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
                            "modelId": BEDROCK_MODEL_FOR_TESTS,
                        },
                        output={"body": ANY_DICT},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        tags=["bedrock", "invoke_model"],
                        metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                        last_updated_at=ANY_BUT_NONE,
                        model=BEDROCK_MODEL_FOR_TESTS,
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


# def test_bedrock_invoke_model__stream_mode_is_on__generator_tracked_correctly(fake_backend):
#     """Test invoke_model_with_response_stream functionality."""
#     client = boto3.client("bedrock-runtime", region_name="us-east-1")
#     tracked_client = track_bedrock(client)

#     request_body = {
#         "anthropic_version": "bedrock-2023-05-31",
#         "max_tokens": 50,
#         "messages": [{"role": "user", "content": "Hello, tell me a story"}]
#     }

#     response = tracked_client.invoke_model_with_response_stream(
#         modelId=BEDROCK_MODEL_FOR_TESTS,
#         body=json.dumps(request_body),
#         contentType="application/json",
#         accept="application/json"
#     )

#     # Consume the stream
#     for _ in response["body"]:
#         pass

#     opik.flush_tracker()

#     expected_trace = TraceModel(
#         id=ANY_BUT_NONE,
#         name="bedrock_invoke_model_stream",
#         input={"body": request_body, "modelId": BEDROCK_MODEL_FOR_TESTS},
#         output={"body": ANY_DICT},
#         start_time=ANY_BUT_NONE,
#         end_time=ANY_BUT_NONE,
#         tags=["bedrock", "invoke_model"],
#         metadata=ANY_DICT,
#         last_updated_at=ANY_BUT_NONE,
#         spans=[
#             SpanModel(
#                 id=ANY_BUT_NONE,
#                 name="bedrock_invoke_model_stream",
#                 type="llm",
#                 input={"body": request_body, "modelId": BEDROCK_MODEL_FOR_TESTS},
#                 output={"body": ANY_DICT},
#                 start_time=ANY_BUT_NONE,
#                 end_time=ANY_BUT_NONE,
#                 tags=["bedrock", "invoke_model"],
#                 metadata=ANY_DICT.containing({"created_from": "bedrock"}),
#                 last_updated_at=ANY_BUT_NONE,
#                 model=BEDROCK_MODEL_FOR_TESTS,
#                 usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
#                 provider="bedrock",
#                 spans=[],
#             )
#         ],
#     )
#     assert len(fake_backend.trace_trees) == 1

#     trace_tree = fake_backend.trace_trees[0]
#     assert_equal(expected_trace, trace_tree)


# def test_bedrock_invoke_model__stream_called_2_times__generator_tracked_correctly(
#     fake_backend,
# ):
#     """Test that multiple invoke_model_with_response_stream calls create separate spans."""
#     client = boto3.client("bedrock-runtime", region_name="us-east-1")
#     tracked_client = track_bedrock(client)

#     # Make first stream call
#     request_body1 = {
#         "anthropic_version": "bedrock-2023-05-31",
#         "max_tokens": 20,
#         "messages": [{"role": "user", "content": "Hello"}]
#     }

#     response1 = tracked_client.invoke_model_with_response_stream(
#         modelId=BEDROCK_MODEL_FOR_TESTS,
#         body=json.dumps(request_body1),
#         contentType="application/json",
#         accept="application/json"
#     )

#     # Consume the first stream
#     for _ in response1["body"]:
#         pass

#     # Make second stream call
#     request_body2 = {
#         "anthropic_version": "bedrock-2023-05-31",
#         "max_tokens": 20,
#         "messages": [{"role": "user", "content": "Goodbye"}]
#     }

#     response2 = tracked_client.invoke_model_with_response_stream(
#         modelId=BEDROCK_MODEL_FOR_TESTS,
#         body=json.dumps(request_body2),
#         contentType="application/json",
#         accept="application/json"
#     )

#     # Consume the second stream
#     for _ in response2["body"]:
#         pass

#     opik.flush_tracker()

#     # Should have two separate trace trees
#     assert len(fake_backend.trace_trees) == 2

#     # Verify first trace
#     expected_trace1 = TraceModel(
#         id=ANY_BUT_NONE,
#         name="bedrock_invoke_model_stream",
#         input={"body": request_body1, "modelId": BEDROCK_MODEL_FOR_TESTS},
#         output={"body": ANY_DICT},
#         start_time=ANY_BUT_NONE,
#         end_time=ANY_BUT_NONE,
#         tags=["bedrock", "invoke_model"],
#         metadata=ANY_DICT,
#         last_updated_at=ANY_BUT_NONE,
#         spans=[
#             SpanModel(
#                 id=ANY_BUT_NONE,
#                 name="bedrock_invoke_model_stream",
#                 type="llm",
#                 input={"body": request_body1, "modelId": BEDROCK_MODEL_FOR_TESTS},
#                 output={"body": ANY_DICT},
#                 start_time=ANY_BUT_NONE,
#                 end_time=ANY_BUT_NONE,
#                 tags=["bedrock", "invoke_model"],
#                 metadata=ANY_DICT.containing({"created_from": "bedrock"}),
#                 last_updated_at=ANY_BUT_NONE,
#                 model=BEDROCK_MODEL_FOR_TESTS,
#                 usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
#                 provider="bedrock",
#                 spans=[],
#             )
#         ],
#     )

#     # Verify second trace
#     expected_trace2 = TraceModel(
#         id=ANY_BUT_NONE,
#         name="bedrock_invoke_model_stream",
#         input={"body": request_body2, "modelId": BEDROCK_MODEL_FOR_TESTS},
#         output={"body": ANY_DICT},
#         start_time=ANY_BUT_NONE,
#         end_time=ANY_BUT_NONE,
#         tags=["bedrock", "invoke_model"],
#         metadata=ANY_DICT,
#         last_updated_at=ANY_BUT_NONE,
#         spans=[
#             SpanModel(
#                 id=ANY_BUT_NONE,
#                 name="bedrock_invoke_model_stream",
#                 type="llm",
#                 input={"body": request_body2, "modelId": BEDROCK_MODEL_FOR_TESTS},
#                 output={"body": ANY_DICT},
#                 start_time=ANY_BUT_NONE,
#                 end_time=ANY_BUT_NONE,
#                 tags=["bedrock", "invoke_model"],
#                 metadata=ANY_DICT.containing({"created_from": "bedrock"}),
#                 last_updated_at=ANY_BUT_NONE,
#                 model=BEDROCK_MODEL_FOR_TESTS,
#                 usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
#                 provider="bedrock",
#                 spans=[],
#             )
#         ],
#     )

#     assert_equal(expected_trace1, fake_backend.trace_trees[0])
#     assert_equal(expected_trace2, fake_backend.trace_trees[1])
