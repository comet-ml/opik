"""A LiteLLM stream that fails part-way through still ends its span with an error.

Runs offline: `mock_response` makes LiteLLM build a real `CustomStreamWrapper`
over a canned response, and `fake_backend` captures what Opik would log. These
tests do not need an OpenAI key, so unlike the rest of this directory they carry
no `ensure_openai_configured` marker.
"""

import pytest

import litellm

import opik
from opik.integrations.litellm import track_completion

MODEL = "gpt-4o-mini"
MESSAGES = [{"role": "user", "content": "hi"}]


def _tracked_stream(**kwargs):
    return track_completion()(litellm.completion)(
        model=MODEL,
        messages=MESSAGES,
        mock_response="hello world",
        stream=True,
        **kwargs,
    )


def _fail_mid_stream(stream, after=0):
    """Let the underlying provider stream deliver `after` chunks, then raise.

    This is what a dropped connection, a 429, or LiteLLM's own
    `MidStreamFallbackError` look like from the wrapper's point of view: some
    chunks arrive, then `__next__` raises. LiteLLM re-wraps whatever the
    provider raised, so what the caller and the span see is a
    `MidStreamFallbackError` carrying the original message.
    """
    inner = stream.completion_stream

    def failing():
        for index, chunk in enumerate(inner):
            if index >= after:
                raise RuntimeError("litellm-stream-blew-up")
            yield chunk

    stream.completion_stream = failing()


async def _afail_mid_stream(stream, after=0):
    inner = stream.completion_stream

    async def failing():
        index = 0
        async for chunk in inner:
            if index >= after:
                raise RuntimeError("litellm-stream-blew-up")
            index += 1
            yield chunk

    stream.completion_stream = failing()


def test_litellm_completion_stream__fails_mid_stream__span_ends_with_error_info(
    fake_backend,
):
    stream = _tracked_stream()
    _fail_mid_stream(stream, after=1)

    with pytest.raises(Exception, match="litellm-stream-blew-up"):
        for _ in stream:
            pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    logged_span = fake_backend.trace_trees[0].spans[0]

    # The failure has to be visible in the trace: a span that is never ended and
    # carries no error_info reads as a call that is still running, forever.
    assert logged_span.end_time is not None
    assert logged_span.error_info is not None
    assert logged_span.error_info["exception_type"] == "MidStreamFallbackError"
    assert "litellm-stream-blew-up" in logged_span.error_info["message"]
    # A failed stream has no usable output to report.
    assert logged_span.output is None

    # Nothing of ours is left behind on the stream object.
    assert not hasattr(stream, "opik_tracked_instance")
    assert not hasattr(stream, "_opik_accumulated_items")
    assert not hasattr(stream, "_opik_error_info")


@pytest.mark.asyncio
async def test_litellm_acompletion_stream__fails_mid_stream__span_ends_with_error_info(
    fake_backend,
):
    stream = await track_completion()(litellm.acompletion)(
        model=MODEL, messages=MESSAGES, mock_response="hello world", stream=True
    )
    await _afail_mid_stream(stream, after=1)

    with pytest.raises(Exception, match="litellm-stream-blew-up"):
        async for _ in stream:
            pass

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is not None
    assert logged_span.error_info["exception_type"] == "MidStreamFallbackError"
    assert "litellm-stream-blew-up" in logged_span.error_info["message"]
    assert logged_span.output is None

    assert not hasattr(stream, "opik_tracked_instance_async")
    assert not hasattr(stream, "_opik_accumulated_items_async")
    assert not hasattr(stream, "_opik_error_info_async")


def test_litellm_completion_stream__completes__span_still_ends_with_output(
    fake_backend,
):
    """The happy path must be unaffected: output aggregated, no error recorded."""
    stream = _tracked_stream()

    text = ""
    for chunk in stream:
        text += chunk.choices[0].delta.content or ""

    opik.flush_tracker()

    assert text == "hello world"
    assert len(fake_backend.trace_trees) == 1
    logged_span = fake_backend.trace_trees[0].spans[0]

    assert logged_span.end_time is not None
    assert logged_span.error_info is None
    assert logged_span.output["choices"][0]["message"]["content"] == "hello world"
