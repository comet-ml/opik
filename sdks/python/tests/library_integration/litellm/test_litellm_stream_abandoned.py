"""A LiteLLM stream the caller stops reading early still ends its span.

Runs offline: `mock_response` makes LiteLLM build a real `CustomStreamWrapper`
over a canned response, and `fake_backend` captures what Opik would log, so no
provider key is needed.

Stopping early is ordinary: a `break` once enough has arrived, a `return` from
inside the loop, a caller-side timeout. The span records what arrived before
the caller stopped, flagged with `stream_completed: False` in its metadata.
"""

import asyncio
import gc
import time
import weakref

import litellm
import pytest

import opik
from opik.integrations.litellm import track_completion

from . import constants

MODEL_FOR_TESTS = constants.MODEL_FOR_TESTS
MESSAGES = [{"role": "user", "content": "hi"}]
MOCK_RESPONSE = "hello world, this is a longer answer"


def _tracked_stream():
    return track_completion()(litellm.completion)(
        model=MODEL_FOR_TESTS,
        messages=MESSAGES,
        mock_response=MOCK_RESPONSE,
        stream=True,
    )


def _content(span):
    return span.output["choices"][0]["message"]["content"]


def test_litellm_completion_stream__consumer_breaks__span_ends_with_partial_output(
    fake_backend,
):
    stream = _tracked_stream()

    received = ""
    for chunk in stream:
        received += chunk.choices[0].delta.content or ""
        break

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert _content(logged_span) == received
    assert received != MOCK_RESPONSE
    assert logged_span.metadata["stream_completed"] is False
    # The model never said it was done; don't claim it did.
    assert logged_span.output["choices"][0]["finish_reason"] is None
    assert fake_backend.trace_trees[0].end_time is not None


def test_litellm_completion_stream__consumer_breaks_after_finish_reason__finish_reason_kept(
    fake_backend,
):
    stream = _tracked_stream()

    for chunk in stream:
        if chunk.choices[0].finish_reason:
            break

    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.output["choices"][0]["finish_reason"] == "stop"


def test_litellm_completion_stream__returned_from_inside_tracked_function__child_span_ends(
    fake_backend,
):
    @opik.track
    def agent():
        for chunk in _tracked_stream():
            return chunk.choices[0].delta.content

    first_chunk = agent()

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    agent_span = fake_backend.trace_trees[0].spans[0]
    llm_span = agent_span.spans[0]

    # The parent finishing must not leave its LLM call looking like it is still running.
    assert agent_span.end_time is not None
    assert llm_span.end_time is not None
    assert llm_span.error_info is None
    assert _content(llm_span) == first_chunk
    assert llm_span.metadata["stream_completed"] is False


def test_litellm_completion_stream__consumer_loop_body_raises__span_ends_without_error(
    fake_backend,
):
    with pytest.raises(ValueError, match="consumer bug"):
        for _ in _tracked_stream():
            raise ValueError("consumer bug")

    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    # The LLM call itself did not fail: the error belongs to the caller's code.
    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert logged_span.metadata["stream_completed"] is False


def _wait_until_collected(stream_ref):
    """Wait until the dropped stream is actually collected.

    LiteLLM's logging executor holds the stream for a moment after each chunk,
    so it is not freed the instant the caller lets go.
    """
    ref = stream_ref
    deadline = time.monotonic() + 5
    while ref() is not None and time.monotonic() < deadline:
        gc.collect()
        time.sleep(0.05)
    assert ref() is None, "the stream was never collected"


def test_litellm_completion_stream__read_with_next_then_dropped__span_ends_with_partial_output(
    fake_backend,
):
    stream = _tracked_stream()
    first = next(stream).choices[0].delta.content

    stream_ref = weakref.ref(stream)
    del stream
    _wait_until_collected(stream_ref)
    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert _content(logged_span) == first
    assert logged_span.metadata["stream_completed"] is False


def test_litellm_completion_stream__never_read_then_dropped__span_ends_flagged_incomplete(
    fake_backend,
):
    stream = _tracked_stream()

    stream_ref = weakref.ref(stream)
    del stream
    _wait_until_collected(stream_ref)
    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert logged_span.metadata["stream_completed"] is False


def test_litellm_completion_stream__fully_read_then_dropped__span_ends_once_unflagged(
    fake_backend,
):
    stream = _tracked_stream()
    received = "".join(chunk.choices[0].delta.content or "" for chunk in stream)

    # Every other way of ending the span still gets its chance afterwards.
    stream_ref = weakref.ref(stream)
    del stream
    _wait_until_collected(stream_ref)
    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert len(fake_backend.trace_trees[0].spans) == 1
    logged_span = fake_backend.trace_trees[0].spans[0]

    assert received == MOCK_RESPONSE
    assert _content(logged_span) == MOCK_RESPONSE
    assert "stream_completed" not in logged_span.metadata


def test_litellm_completion_stream__interrupted_inside_next__span_ends_with_error_info(
    fake_backend,
):
    stream = _tracked_stream()
    inner = stream.completion_stream

    def interrupted():
        for index, chunk in enumerate(inner):
            if index == 1:
                raise KeyboardInterrupt
            yield chunk

    stream.completion_stream = interrupted()

    with pytest.raises(KeyboardInterrupt):
        for _ in stream:
            pass

    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info["exception_type"] == "KeyboardInterrupt"


async def _tracked_async_stream():
    return await track_completion()(litellm.acompletion)(
        model=MODEL_FOR_TESTS,
        messages=MESSAGES,
        mock_response=MOCK_RESPONSE,
        stream=True,
    )


@pytest.mark.asyncio
async def test_litellm_acompletion_stream__consumer_breaks__span_ends_with_partial_output(
    fake_backend,
):
    stream = await _tracked_async_stream()

    received = ""
    async for chunk in stream:
        received += chunk.choices[0].delta.content or ""
        break
    # A dropped async iterator is closed by the event loop on its next turn,
    # the same way asyncio finalizes any abandoned async generator.
    await asyncio.sleep(0.05)

    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert _content(logged_span) == received
    assert logged_span.metadata["stream_completed"] is False


@pytest.mark.asyncio
async def test_litellm_acompletion_stream__closed_with_aclose__span_ends_with_partial_output(
    fake_backend,
):
    stream = await _tracked_async_stream()

    first = (await stream.__anext__()).choices[0].delta.content
    await stream.aclose()

    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert _content(logged_span) == first
    assert logged_span.metadata["stream_completed"] is False


@pytest.mark.asyncio
async def test_litellm_acompletion_stream__timeout_while_waiting_for_chunk__span_ends_with_error_info(
    fake_backend,
):
    stream = await _tracked_async_stream()
    inner = stream.completion_stream

    async def stalled():
        async for chunk in inner:
            await asyncio.sleep(10)
            yield chunk

    stream.completion_stream = stalled()

    async def consume():
        async for _ in stream:
            pass

    with pytest.raises(asyncio.TimeoutError):
        await asyncio.wait_for(consume(), timeout=0.2)

    opik.flush_tracker()

    logged_span = fake_backend.trace_trees[0].spans[0]

    # The caller gave up on the call: that is a failure worth seeing, unlike a
    # caller that simply stopped reading.
    assert logged_span.end_time is not None
    assert logged_span.error_info["exception_type"] == "CancelledError"
