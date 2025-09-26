import asyncio

from typing import Any, Dict

import pytest
from google import genai
from google.genai.types import HttpOptions, GenerateContentConfig
from google.genai import errors as genai_errors
import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.genai import track_genai

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_LIST,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)
import tenacity

pytestmark = pytest.mark.usefixtures("ensure_vertexai_configured")

MODEL = "gemini-2.0-flash"

EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.total_token_count": ANY_BUT_NONE,
    "original_usage.candidates_token_count": ANY_BUT_NONE,
    "original_usage.prompt_token_count": ANY_BUT_NONE,
}


def _is_rate_limit_error(exception: Exception) -> bool:
    if isinstance(exception, genai_errors.ClientError):
        return exception.response.status_code == 429
    return False


retry_with_waiting_on_rate_limit_errors = tenacity.retry(
    stop=tenacity.stop_after_attempt(3),
    wait=tenacity.wait_incrementing(start=5, increment=5),
    retry=tenacity.retry_if_exception(_is_rate_limit_error),
)


def _assert_metadata_contains_required_keys(metadata: Dict[str, Any]):
    REQUIRED_METADATA_KEYS = [
        "model",
        "created_from",
        "model_version",
        "usage_metadata",
    ]
    assert_dict_has_keys(metadata, REQUIRED_METADATA_KEYS)


@retry_with_waiting_on_rate_limit_errors
@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("genai-integration-test", "genai-integration-test"),
    ],
)
def test_genai_client__generate_content__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client, project_name=project_name)

    client.models.generate_content(
        model=MODEL,
        contents="What is the capital of Belarus?",
        config=GenerateContentConfig(max_output_tokens=10),
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name=ANY_STRING.starting_with(f"generate_content: {MODEL}"),
        input={"contents": "What is the capital of Belarus?", "config": ANY_BUT_NONE},
        output={"candidates": ANY_LIST},
        tags=["genai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=ANY_STRING.starting_with(f"generate_content: {MODEL}"),
                input={
                    "contents": "What is the capital of Belarus?",
                    "config": ANY_BUT_NONE,
                },
                output={"candidates": ANY_LIST},
                tags=["genai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                project_name=expected_project_name,
                spans=[],
                model=ANY_STRING.starting_with(MODEL),
                provider="google_vertexai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
def test_genai_client__async_generate_content__happyflow(fake_backend):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    response = client.aio.models.generate_content(
        model=MODEL,
        contents="What is the capital of Belarus?",
    )
    asyncio.run(response)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name=ANY_STRING.starting_with(f"async_generate_content: {MODEL}"),
        input={"contents": "What is the capital of Belarus?"},
        output={"candidates": ANY_LIST},
        tags=["genai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=ANY_STRING.starting_with(f"async_generate_content: {MODEL}"),
                input={"contents": "What is the capital of Belarus?"},
                output={"candidates": ANY_LIST},
                tags=["genai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL),
                provider="google_vertexai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
@pytest.mark.asyncio
async def test_genai_client__async_generate_content__opik_args__happyflow(fake_backend):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    args_dict = {
        "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
        "trace": {
            "thread_id": "conversation-2",
            "tags": ["trace_tag"],
            "metadata": {"trace_key": "trace_value"},
        },
    }

    _ = await client.aio.models.generate_content(
        model=MODEL,
        contents="What is the capital of Belarus?",
        opik_args=args_dict,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name=ANY_STRING.starting_with(f"async_generate_content: {MODEL}"),
        input={"contents": "What is the capital of Belarus?"},
        output={"candidates": ANY_LIST},
        tags=["genai", "span_tag", "trace_tag"],
        metadata=ANY_DICT.containing({"trace_key": "trace_value"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        thread_id="conversation-2",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=ANY_STRING.starting_with(f"async_generate_content: {MODEL}"),
                input={"contents": "What is the capital of Belarus?"},
                output={"candidates": ANY_LIST},
                tags=["genai", "span_tag"],
                metadata=ANY_DICT.containing({"span_key": "span_value"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL),
                provider="google_vertexai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("genai-integration-test", "genai-integration-test"),
    ],
)
def test_genai_client__generate_content_called_inside_another_tracked_function__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    @opik.track(project_name=project_name)
    def f():
        client.models.generate_content(
            model=MODEL,
            contents="What is the capital of Belarus?",
        )

    f()
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name=ANY_STRING.starting_with(f"generate_content: {MODEL}"),
                        input={"contents": "What is the capital of Belarus?"},
                        output={"candidates": ANY_LIST},
                        tags=["genai"],
                        metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                        project_name=expected_project_name,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL),
                        provider="google_vertexai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
def test_genai_client__async_generate_content_called_inside_another_tracked_function__happyflow(
    fake_backend,
):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    @opik.track
    async def f():
        _ = await client.aio.models.generate_content(
            model=MODEL,
            contents="What is the capital of Belarus?",
        )

    asyncio.run(f())
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name=ANY_STRING.starting_with(
                            f"async_generate_content: {MODEL}"
                        ),
                        input={"contents": "What is the capital of Belarus?"},
                        output={"candidates": ANY_LIST},
                        tags=["genai"],
                        metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL),
                        provider="google_vertexai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
def test_genai_client__generate_content_stream__happyflow(fake_backend):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client, project_name="genai-integration-test")

    stream = client.models.generate_content_stream(
        model=MODEL,
        contents="What is the capital of Belarus?",
    )
    for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name=ANY_STRING.starting_with(f"generate_content_stream: {MODEL}"),
        input={"contents": "What is the capital of Belarus?"},
        output={"candidates": ANY_LIST},
        tags=["genai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name="genai-integration-test",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=ANY_STRING.starting_with(f"generate_content_stream: {MODEL}"),
                input={"contents": "What is the capital of Belarus?"},
                output={"candidates": ANY_LIST},
                tags=["genai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                project_name="genai-integration-test",
                spans=[],
                model=ANY_STRING.starting_with(MODEL),
                provider="google_vertexai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
def test_genai_client__async_generate_content_stream__happyflow(fake_backend):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    async def stream_example():
        stream = await client.aio.models.generate_content_stream(
            model=MODEL,
            contents="What is the capital of Belarus?",
        )
        async for _ in stream:
            pass

    asyncio.run(stream_example())
    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name=ANY_STRING.starting_with(f"async_generate_content_stream: {MODEL}"),
        input={"contents": "What is the capital of Belarus?"},
        output={"candidates": ANY_LIST},
        tags=["genai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=ANY_STRING.starting_with(
                    f"async_generate_content_stream: {MODEL}"
                ),
                input={"contents": "What is the capital of Belarus?"},
                output={"candidates": ANY_LIST},
                tags=["genai"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                spans=[],
                model=ANY_STRING.starting_with(MODEL),
                provider="google_vertexai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
def test_genai_client__generate_content_stream_called_inside_another_tracked_function__generations_started_after_the_parent_span_closed__llm_span_attached_to_a_parent_function_span(
    fake_backend,
):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    @opik.track
    def f():
        stream = client.models.generate_content_stream(
            model=MODEL,
            contents="What is the capital of Belarus?",
        )
        return stream

    stream = f()
    for _ in stream:
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        output=ANY_BUT_NONE,  # tracked generator
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                output=ANY_BUT_NONE,  # tracked generator
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name=ANY_STRING.starting_with(
                            f"generate_content_stream: {MODEL}"
                        ),
                        input={"contents": "What is the capital of Belarus?"},
                        output={"candidates": ANY_LIST},
                        tags=["genai"],
                        metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL),
                        provider="google_vertexai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
def test_genai_client__async_generate_content_stream_called_inside_another_tracked_function__generations_started_after_the_parent_span_closed__llm_span_has_a_separate_trace(
    fake_backend,
):
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client)

    @opik.track
    async def f():
        stream = await client.aio.models.generate_content_stream(
            model=MODEL,
            contents="What is the capital of Belarus?",
        )
        return stream

    async def stream_outside_of_parent_function_example():
        stream = await f()
        async for _ in stream:
            pass

    asyncio.run(stream_outside_of_parent_function_example())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        output=ANY_BUT_NONE,  # tracked generator
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                output=ANY_BUT_NONE,  # tracked generator
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name=ANY_STRING.starting_with(
                            f"async_generate_content_stream: {MODEL}"
                        ),
                        input={"contents": "What is the capital of Belarus?"},
                        output={"candidates": ANY_LIST},
                        tags=["genai"],
                        metadata=ANY_DICT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                        spans=[],
                        model=ANY_STRING.starting_with(MODEL),
                        provider="google_vertexai",
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@retry_with_waiting_on_rate_limit_errors
@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("genai-integration-test", "genai-integration-test"),
    ],
)
def test_genai_client__generate_content__opik_args__happyflow(
    fake_backend, project_name, expected_project_name
):
    # test that opik_args are passed to the logged traces and spans
    client = genai.Client(
        vertexai=True,
        http_options=HttpOptions(api_version="v1"),
    )
    client = track_genai(client, project_name=project_name)

    args_dict = {
        "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
        "trace": {
            "thread_id": "conversation-2",
            "tags": ["trace_tag"],
            "metadata": {"trace_key": "trace_value"},
        },
    }

    client.models.generate_content(
        model=MODEL,
        contents="What is the capital of Belarus?",
        config=GenerateContentConfig(max_output_tokens=10),
        opik_args=args_dict,
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name=ANY_STRING.starting_with(f"generate_content: {MODEL}"),
        input={"contents": "What is the capital of Belarus?", "config": ANY_BUT_NONE},
        output={"candidates": ANY_LIST},
        tags=["genai", "span_tag", "trace_tag"],
        metadata=ANY_DICT.containing({"trace_key": "trace_value"}),
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        thread_id="conversation-2",
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=ANY_STRING.starting_with(f"generate_content: {MODEL}"),
                input={
                    "contents": "What is the capital of Belarus?",
                    "config": ANY_BUT_NONE,
                },
                output={"candidates": ANY_LIST},
                tags=["genai", "span_tag"],
                metadata=ANY_DICT.containing({"span_key": "span_value"}),
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                usage=EXPECTED_GOOGLE_USAGE_LOGGED_FORMAT,
                project_name=expected_project_name,
                spans=[],
                model=ANY_STRING.starting_with(MODEL),
                provider="google_vertexai",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)
