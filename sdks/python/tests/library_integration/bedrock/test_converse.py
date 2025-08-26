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
def test_bedrock_converse__happyflow(fake_backend, project_name, expected_project_name):
    """Test basic converse functionality with Bedrock client."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client, project_name=project_name)

    messages = [{"role": "user", "content": [{"text": "Hello, how are you?"}]}]

    system_prompt = [
        {
            "text": "You are a helpful AI assistant. Provide concise and accurate responses."
        }
    ]

    _ = tracked_client.converse(
        modelId=BEDROCK_MODEL_FOR_TESTS,
        messages=messages,
        system=system_prompt,
        inferenceConfig={
            "maxTokens": 50,
            "temperature": 0.1,
        },
    )

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_converse",
        input={"messages": messages, "system": system_prompt},
        output={"output": ANY_DICT},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        project_name=expected_project_name,
        tags=["bedrock"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_converse",
                type="llm",
                input={"messages": messages, "system": system_prompt},
                output={"output": ANY_DICT},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock"],
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


def test_bedrock_converse__create_raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    fake_backend,
):
    """Test that errors are properly logged as error spans."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client)

    messages = [{"role": "user", "content": [{"text": "Test message"}]}]

    # Use an invalid model to trigger an error
    with pytest.raises(Exception):
        tracked_client.converse(
            modelId="invalid-model-id",
            messages=messages,
            inferenceConfig={"maxTokens": 50},
        )

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_converse",
        input={"messages": messages},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock"],
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
                name="bedrock_converse",
                type="llm",
                input={"messages": messages},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock"],
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


def test_bedrock_converse__converse_call_made_in_another_tracked_function__bedrock_span_attached_to_existing_trace(
    fake_backend,
):
    """Test that converse calls within tracked functions create proper nesting."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client)

    @opik.track()
    def ask_bedrock_question(question: str) -> str:
        messages = [{"role": "user", "content": [{"text": question}]}]

        response = tracked_client.converse(
            modelId=BEDROCK_MODEL_FOR_TESTS,
            messages=messages,
            inferenceConfig={"maxTokens": 50},
        )

        return response["output"]["message"]["content"][0]["text"]

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
                        name="bedrock_converse",
                        type="llm",
                        input={
                            "messages": [
                                {"role": "user", "content": [{"text": "What is 2+2?"}]}
                            ]
                        },
                        output={"output": ANY_DICT},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        tags=["bedrock"],
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


def test_bedrock_converse__stream_mode_is_on__generator_tracked_correctly(fake_backend):
    """Test converse_stream functionality."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client)

    messages = [{"role": "user", "content": [{"text": "Hello, tell me a story"}]}]

    response = tracked_client.converse_stream(
        modelId=BEDROCK_MODEL_FOR_TESTS,
        messages=messages,
        inferenceConfig={"maxTokens": 50},
    )

    for _ in response["stream"]:
        pass

    opik.flush_tracker()

    expected_trace = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_converse_stream",
        input={"messages": messages},
        output={"output": ANY_DICT},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_converse_stream",
                type="llm",
                input={"messages": messages},
                output={"output": ANY_DICT},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock"],
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


def test_bedrock_converse__stream_called_2_times__generator_tracked_correctly(
    fake_backend,
):
    """Test that multiple converse_stream calls create separate spans."""
    client = boto3.client("bedrock-runtime", region_name="us-east-1")
    tracked_client = track_bedrock(client)

    # Make first stream call
    response1 = tracked_client.converse_stream(
        modelId=BEDROCK_MODEL_FOR_TESTS,
        messages=[{"role": "user", "content": [{"text": "Hello"}]}],
        inferenceConfig={"maxTokens": 20},
    )

    # Consume the first stream
    for _ in response1["stream"]:
        pass

    # Make second stream call
    response2 = tracked_client.converse_stream(
        modelId=BEDROCK_MODEL_FOR_TESTS,
        messages=[{"role": "user", "content": [{"text": "Goodbye"}]}],
        inferenceConfig={"maxTokens": 20},
    )

    # Consume the second stream
    for _ in response2["stream"]:
        pass

    opik.flush_tracker()

    # Should have two separate trace trees
    assert len(fake_backend.trace_trees) == 2

    # Verify first trace
    messages1 = [{"role": "user", "content": [{"text": "Hello"}]}]
    expected_trace1 = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_converse_stream",
        input={"messages": messages1},
        output={"output": ANY_DICT},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_converse_stream",
                type="llm",
                input={"messages": messages1},
                output={"output": ANY_DICT},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=BEDROCK_MODEL_FOR_TESTS,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )

    # Verify second trace
    messages2 = [{"role": "user", "content": [{"text": "Goodbye"}]}]
    expected_trace2 = TraceModel(
        id=ANY_BUT_NONE,
        name="bedrock_converse_stream",
        input={"messages": messages2},
        output={"output": ANY_DICT},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        tags=["bedrock"],
        metadata=ANY_DICT,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="bedrock_converse_stream",
                type="llm",
                input={"messages": messages2},
                output={"output": ANY_DICT},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                tags=["bedrock"],
                metadata=ANY_DICT.containing({"created_from": "bedrock"}),
                last_updated_at=ANY_BUT_NONE,
                model=BEDROCK_MODEL_FOR_TESTS,
                usage=ANY_DICT.containing(EXPECTED_BEDROCK_USAGE_LOGGED_FORMAT),
                provider="bedrock",
                spans=[],
            )
        ],
    )

    assert_equal(expected_trace1, fake_backend.trace_trees[0])
    assert_equal(expected_trace2, fake_backend.trace_trees[1])
