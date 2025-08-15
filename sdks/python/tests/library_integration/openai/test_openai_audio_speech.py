import asyncio
import opik
import openai
import pytest
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.openai import track_openai
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_dict_has_keys,
    assert_equal,
)

MODEL_FOR_TESTS = "tts-1"
INPUT_FOR_TESTS = "Hello, world!"


def _assert_metadata_contains_required_keys(metadata):
    REQUIRED_METADATA_KEYS = [
        "model",
        "voice",
        "created_from",
        "type",
    ]
    assert_dict_has_keys(metadata, REQUIRED_METADATA_KEYS)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("openai-integration-test", "openai-integration-test"),
    ],
)
def test_openai_client_audio_speech_create__happyflow(
    respx_mock, fake_backend, project_name, expected_project_name
):
    respx_mock.post("https://api.aimlapi.com/v1/audio/speech").respond(
        200, content=b"audio data"
    )

    client = openai.OpenAI(api_key="fake-key", base_url="https://api.aimlapi.com/v1")
    wrapped_client = track_openai(
        openai_client=client,
        project_name=project_name,
    )
    _ = wrapped_client.audio.speech.create(
        model=MODEL_FOR_TESTS,
        input=INPUT_FOR_TESTS,
        voice="alloy",
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio_speech_create",
        input={"input": INPUT_FOR_TESTS},
        output={},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio_speech_create",
                input={"input": INPUT_FOR_TESTS},
                output={},
                tags=["openai"],
                metadata=ANY_DICT,
                usage={
                    "total_tokens": len(INPUT_FOR_TESTS),
                    "original_usage.total_tokens": len(INPUT_FOR_TESTS),
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=MODEL_FOR_TESTS,
                provider="api.aimlapi.com",
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


def test_openai_client_audio_speech_create__raises_an_error__span_and_trace_finished_gracefully__error_info_is_logged(
    respx_mock,
    fake_backend,
):
    respx_mock.post("https://api.aimlapi.com/v1/audio/speech").respond(
        400, json={"error": "Bad Request"}
    )
    client = openai.OpenAI(api_key="fake-key", base_url="https://api.aimlapi.com/v1")
    wrapped_client = track_openai(client)

    with pytest.raises(openai.OpenAIError):
        _ = wrapped_client.audio.speech.create(
            model=MODEL_FOR_TESTS,
            input=INPUT_FOR_TESTS,
            voice="alloy",
        )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio_speech_create",
        input={"input": INPUT_FOR_TESTS},
        output=None,
        tags=["openai"],
        metadata={
            "created_from": "openai",
            "type": "openai_audio_speech",
            "model": MODEL_FOR_TESTS,
            "voice": "alloy",
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=ANY_BUT_NONE,
        error_info={
            "exception_type": ANY_STRING,
            "message": ANY_STRING,
            "traceback": ANY_STRING,
        },
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio_speech_create",
                input={"input": INPUT_FOR_TESTS},
                output=None,
                tags=["openai"],
                metadata={
                    "created_from": "openai",
                    "type": "openai_audio_speech",
                    "model": MODEL_FOR_TESTS,
                    "voice": "alloy",
                },
                usage=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=ANY_BUT_NONE,
                model=MODEL_FOR_TESTS,
                provider="api.aimlapi.com",
                error_info={
                    "exception_type": ANY_STRING,
                    "message": ANY_STRING,
                    "traceback": ANY_STRING,
                },
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)


def test_openai_client_audio_speech_create__openai_call_made_in_another_tracked_function__openai_span_attached_to_existing_trace(
    respx_mock,
    fake_backend,
):
    respx_mock.post("https://api.aimlapi.com/v1/audio/speech").respond(
        200, content=b"audio data"
    )
    project_name = "openai-integration-test"

    @opik.track(project_name=project_name)
    def f():
        client = openai.OpenAI(
            api_key="fake-key", base_url="https://api.aimlapi.com/v1"
        )
        wrapped_client = track_openai(
            openai_client=client,
            project_name="openai-integration-test-nested-level",
        )

        _ = wrapped_client.audio.speech.create(
            model=MODEL_FOR_TESTS,
            input=INPUT_FOR_TESTS,
            voice="alloy",
        )

    f()

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={},
        output=None,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={},
                output=None,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=project_name,
                model=None,
                provider=None,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        type="llm",
                        name="audio_speech_create",
                        input={"input": INPUT_FOR_TESTS},
                        output={},
                        tags=["openai"],
                        metadata=ANY_DICT,
                        usage={
                            "total_tokens": len(INPUT_FOR_TESTS),
                            "original_usage.total_tokens": len(INPUT_FOR_TESTS),
                        },
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        model=MODEL_FOR_TESTS,
                        provider="api.aimlapi.com",
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


def test_openai_client_audio_speech_create__stream_mode_is_on__generator_tracked_correctly(
    respx_mock,
    fake_backend,
):
    respx_mock.post("https://api.aimlapi.com/v1/audio/speech").respond(
        200, content=b"audio data"
    )
    client = openai.OpenAI(api_key="fake-key", base_url="https://api.aimlapi.com/v1")
    wrapped_client = track_openai(client)

    with wrapped_client.audio.speech.with_streaming_response.create(
        model=MODEL_FOR_TESTS,
        input=INPUT_FOR_TESTS,
        voice="alloy",
    ) as stream:
        for item in stream.iter_bytes():
            pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio_speech_create",
        input={"input": INPUT_FOR_TESTS},
        output={},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio_speech_create",
                input={"input": INPUT_FOR_TESTS},
                output={},
                tags=["openai"],
                metadata=ANY_DICT,
                usage={
                    "total_tokens": len(INPUT_FOR_TESTS),
                    "original_usage.total_tokens": len(INPUT_FOR_TESTS),
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=MODEL_FOR_TESTS,
                provider="api.aimlapi.com",
            )
        ],
    )
    assert_equal(EXPECTED_TRACE_TREE, trace_tree)
    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)


@pytest.mark.asyncio
async def test_async_openai_client_audio_speech_create__happyflow(
    respx_mock, fake_backend
):
    respx_mock.post("https://api.aimlapi.com/v1/audio/speech").respond(
        200, content=b"audio data"
    )
    client = openai.AsyncOpenAI(
        api_key="fake-key", base_url="https://api.aimlapi.com/v1"
    )
    wrapped_client = track_openai(
        openai_client=client,
    )

    await wrapped_client.audio.speech.create(
        model=MODEL_FOR_TESTS,
        input=INPUT_FOR_TESTS,
        voice="alloy",
    )

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="audio_speech_create",
        input={"input": INPUT_FOR_TESTS},
        output={},
        tags=["openai"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="audio_speech_create",
                input={"input": INPUT_FOR_TESTS},
                output={},
                tags=["openai"],
                metadata=ANY_DICT,
                usage={
                    "total_tokens": len(INPUT_FOR_TESTS),
                    "original_usage.total_tokens": len(INPUT_FOR_TESTS),
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=MODEL_FOR_TESTS,
                provider="api.aimlapi.com",
            )
        ],
    )
    assert len(fake_backend.trace_trees) == 1
    trace_tree = fake_backend.trace_trees[0]

    assert_equal(EXPECTED_TRACE_TREE, trace_tree)

    llm_span_metadata = trace_tree.spans[0].metadata
    _assert_metadata_contains_required_keys(llm_span_metadata)
