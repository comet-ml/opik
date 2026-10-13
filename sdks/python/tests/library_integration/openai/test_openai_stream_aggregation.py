"""Each tracked OpenAI stream is aggregated with its own API's aggregator.

Chat Completions and Responses streams are both `openai.Stream` objects, but
their events are aggregated differently. Creating a stream with one API must
not change how an already-created stream of the other API is logged.
"""

import logging

import openai
import pytest

import opik
from opik.integrations.openai import track_openai

from ... import llm_constants
from .constants import MODEL_FOR_TESTS

pytestmark = pytest.mark.usefixtures("ensure_openai_configured")

CHAT_MESSAGES = [{"role": "user", "content": "Say hi"}]
RESPONSES_INPUT = "Say hi"


def _create_chat_stream(client):
    return client.chat.completions.create(
        model=MODEL_FOR_TESTS,
        messages=CHAT_MESSAGES,
        max_completion_tokens=64,
        reasoning_effort=llm_constants.OPENAI_REASONING_EFFORT,
        stream=True,
    )


def _create_responses_stream(client):
    return client.responses.create(
        model=MODEL_FOR_TESTS,
        input=RESPONSES_INPUT,
        max_output_tokens=64,
        reasoning={"effort": llm_constants.OPENAI_REASONING_EFFORT},
        stream=True,
    )


def _span(fake_backend, name_prefix):
    return next(
        span
        for trace in fake_backend.trace_trees
        for span in trace.spans
        if span.name.startswith(name_prefix)
    )


def _error_records(caplog):
    # Only opik's own errors count: an unclosed AsyncOpenAI client from an
    # earlier test can be garbage-collected mid-test, and its aclose() task
    # then logs "Event loop is closed" via asyncio.
    return [
        record
        for record in caplog.records
        if record.levelno >= logging.ERROR and record.name.startswith("opik")
    ]


def test_openai_chat_stream__responses_stream_created_before_it_is_read__chat_output_logged(
    fake_backend, caplog
):
    client = track_openai(openai.OpenAI())
    chat_stream = _create_chat_stream(client)
    other_stream = _create_responses_stream(client)

    with caplog.at_level(logging.ERROR):
        for _ in chat_stream:
            pass
    other_stream.close()

    opik.flush_tracker()

    chat_span = _span(fake_backend, "chat_completion")
    assert chat_span.output is not None
    assert chat_span.output["choices"]
    assert not _error_records(caplog)


def test_openai_responses_stream__chat_stream_created_before_it_is_read__responses_output_logged(
    fake_backend, caplog
):
    client = track_openai(openai.OpenAI())
    responses_stream = _create_responses_stream(client)
    other_stream = _create_chat_stream(client)

    with caplog.at_level(logging.ERROR):
        for _ in responses_stream:
            pass
    other_stream.close()

    opik.flush_tracker()

    responses_span = _span(fake_backend, "responses")
    assert responses_span.output is not None
    assert responses_span.output["output"]
    assert not _error_records(caplog)


@pytest.mark.asyncio
async def test_openai_async_chat_stream__responses_stream_created_before_it_is_read__chat_output_logged(
    fake_backend, caplog
):
    client = track_openai(openai.AsyncOpenAI())
    chat_stream = await _create_chat_stream(client)
    other_stream = await _create_responses_stream(client)

    with caplog.at_level(logging.ERROR):
        async for _ in chat_stream:
            pass
    await other_stream.close()

    opik.flush_tracker()

    chat_span = _span(fake_backend, "chat_completion")
    assert chat_span.output is not None
    assert chat_span.output["choices"]
    assert not _error_records(caplog)


@pytest.mark.asyncio
async def test_openai_async_responses_stream__chat_stream_created_before_it_is_read__responses_output_logged(
    fake_backend, caplog
):
    client = track_openai(openai.AsyncOpenAI())
    responses_stream = await _create_responses_stream(client)
    other_stream = await _create_chat_stream(client)

    with caplog.at_level(logging.ERROR):
        async for _ in responses_stream:
            pass
    await other_stream.close()

    opik.flush_tracker()

    responses_span = _span(fake_backend, "responses")
    assert responses_span.output is not None
    assert responses_span.output["output"]
    assert not _error_records(caplog)
