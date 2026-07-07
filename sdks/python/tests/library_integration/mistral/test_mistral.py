import asyncio
import os

import mistralai
import pydantic
import pytest

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.mistral import track_mistral
from opik.types import LLMProvider
from ... import llm_constants
from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

pytestmark = pytest.mark.usefixtures("ensure_mistral_configured")


class _Person(pydantic.BaseModel):
    name: str
    age: int


PARSE_MESSAGES = [{"role": "user", "content": "Extract this person: John is 30."}]

MODEL_FOR_TESTS = llm_constants.MISTRAL_SMALL
EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT = {
    "prompt_tokens": ANY_BUT_NONE,
    "completion_tokens": ANY_BUT_NONE,
    "total_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens": ANY_BUT_NONE,
    "original_usage.completion_tokens": ANY_BUT_NONE,
    "original_usage.total_tokens": ANY_BUT_NONE,
    "original_usage.prompt_tokens_details.cached_tokens": ANY_BUT_NONE,
}

MESSAGES = [{"role": "user", "content": "Tell a short fact"}]


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("mistral-integration-test", "mistral-integration-test"),
    ],
)
def test_mistral_chat_complete__happyflow(
    fake_backend, project_name, expected_project_name
):
    client = track_mistral(
        mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]),
        project_name=project_name,
    )

    _ = client.chat.complete(model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": MESSAGES}),
        output={"choices": ANY_BUT_NONE},
        tags=["mistral"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["mistral"],
                metadata=ANY_DICT,
                usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                spans=[],
                model=ANY_BUT_NONE,
                provider="mistral",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_mistral_chat_complete_async__happyflow(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    async def async_call():
        return await client.chat.complete_async(
            model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10
        )

    _ = asyncio.run(async_call())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": MESSAGES}),
        output={"choices": ANY_BUT_NONE},
        tags=["mistral"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["mistral"],
                metadata=ANY_DICT,
                usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_BUT_NONE,
                provider="mistral",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_mistral_chat_stream__happyflow(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    for _ in client.chat.stream(
        model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10
    ):
        pass

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input=ANY_DICT.containing({"messages": MESSAGES}),
        output={"choices": ANY_BUT_NONE},
        tags=["mistral"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["mistral"],
                metadata=ANY_DICT,
                usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_BUT_NONE,
                provider="mistral",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_mistral_chat_stream_async__happyflow(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    async def async_call():
        async for _ in await client.chat.stream_async(
            model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10
        ):
            pass

    asyncio.run(async_call())

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_stream",
        input=ANY_DICT.containing({"messages": MESSAGES}),
        output={"choices": ANY_BUT_NONE},
        tags=["mistral"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_stream",
                input=ANY_DICT.containing({"messages": MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["mistral"],
                metadata=ANY_DICT,
                usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_BUT_NONE,
                provider="mistral",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_mistral_chat_complete__custom_provider__provider_logged_but_usage_still_parsed(
    fake_backend,
):
    client = track_mistral(
        mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]),
        provider="my-mistral-host",
    )

    _ = client.chat.complete(model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": MESSAGES}),
        output={"choices": ANY_BUT_NONE},
        tags=["mistral"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["mistral"],
                metadata=ANY_DICT,
                usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_BUT_NONE,
                provider="my-mistral-host",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_mistral_provider_enum_accepted(fake_backend):
    client = track_mistral(
        mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]),
        provider=LLMProvider.MISTRALAI,
    )

    _ = client.chat.complete(model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10)

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].provider == "mistral"


def test_mistral_chat_complete__error_raised__span_and_trace_finished__error_info_logged(
    fake_backend,
):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    with pytest.raises(mistralai.models.SDKError):
        client.chat.complete(model="does-not-exist-xyz", messages=MESSAGES)

    opik.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat_completion_create",
        input=ANY_DICT.containing({"messages": MESSAGES}),
        output=None,
        tags=["mistral"],
        metadata=ANY_DICT,
        error_info={
            "exception_type": "SDKError",
            "message": ANY_BUT_NONE,
            "traceback": ANY_BUT_NONE,
        },
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name="chat_completion_create",
                input=ANY_DICT.containing({"messages": MESSAGES}),
                output=None,
                tags=["mistral"],
                metadata=ANY_DICT,
                error_info={
                    "exception_type": "SDKError",
                    "message": ANY_BUT_NONE,
                    "traceback": ANY_BUT_NONE,
                },
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model="does-not-exist-xyz",
                provider="mistral",
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def _expected_parse_trace(span_name: str) -> TraceModel:
    # A single llm span (no nested primitive span) proves parse doesn't
    # double-log through the complete/stream method it calls internally.
    return TraceModel(
        id=ANY_BUT_NONE,
        name=span_name,
        input=ANY_DICT.containing({"messages": PARSE_MESSAGES}),
        output={"choices": ANY_BUT_NONE},
        tags=["mistral"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=OPIK_PROJECT_DEFAULT_NAME,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                type="llm",
                name=span_name,
                input=ANY_DICT.containing({"messages": PARSE_MESSAGES}),
                output={"choices": ANY_BUT_NONE},
                tags=["mistral"],
                metadata=ANY_DICT,
                usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=OPIK_PROJECT_DEFAULT_NAME,
                spans=[],
                model=ANY_BUT_NONE,
                provider="mistral",
                source="sdk",
            )
        ],
        source="sdk",
    )


def test_mistral_chat_parse__happyflow__single_span(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    response = client.chat.parse(
        model=MODEL_FOR_TESTS,
        messages=PARSE_MESSAGES,
        response_format=_Person,
        max_tokens=50,
    )

    opik.flush_tracker()

    # parse() delegates to complete(); only the primitive is patched, so this
    # produces exactly one span (asserted via the single-span trace tree below),
    # named after the parse call thanks to the response_format kwarg.
    assert response.choices[0].message.parsed == _Person(name="John", age=30)
    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_parse_trace("chat_completion_parse"), fake_backend.trace_trees[0]
    )


def test_mistral_chat_parse_async__happyflow(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    async def async_call():
        return await client.chat.parse_async(
            model=MODEL_FOR_TESTS,
            messages=PARSE_MESSAGES,
            response_format=_Person,
            max_tokens=50,
        )

    asyncio.run(async_call())

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_parse_trace("chat_completion_parse"), fake_backend.trace_trees[0]
    )


def test_mistral_chat_parse_stream__happyflow(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    for _ in client.chat.parse_stream(
        model=MODEL_FOR_TESTS,
        messages=PARSE_MESSAGES,
        response_format=_Person,
        max_tokens=50,
    ):
        pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_parse_trace("chat_completion_parse_stream"),
        fake_backend.trace_trees[0],
    )


def test_mistral_chat_parse_stream_async__happyflow(fake_backend):
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    async def async_call():
        async for _ in await client.chat.parse_stream_async(
            model=MODEL_FOR_TESTS,
            messages=PARSE_MESSAGES,
            response_format=_Person,
            max_tokens=50,
        ):
            pass

    asyncio.run(async_call())

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert_equal(
        _expected_parse_trace("chat_completion_parse_stream"),
        fake_backend.trace_trees[0],
    )


def test_mistral_chat_complete__called_in_tracked_function__span_nested_under_track(
    fake_backend,
):
    project_name = "mistral-integration-test"
    client = track_mistral(mistralai.Mistral(api_key=os.environ["MISTRAL_API_KEY"]))

    @opik.track(project_name=project_name)
    def f():
        client.chat.complete(model=MODEL_FOR_TESTS, messages=MESSAGES, max_tokens=10)

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
                        name="chat_completion_create",
                        input=ANY_DICT.containing({"messages": MESSAGES}),
                        output={"choices": ANY_BUT_NONE},
                        tags=["mistral"],
                        metadata=ANY_DICT,
                        usage=EXPECTED_MISTRAL_USAGE_LOGGED_FORMAT,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        project_name=project_name,
                        spans=[],
                        model=ANY_BUT_NONE,
                        provider="mistral",
                        source="sdk",
                    )
                ],
                source="sdk",
            )
        ],
        source="sdk",
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])
