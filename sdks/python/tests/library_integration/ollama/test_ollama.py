import asyncio

import ollama
import pytest
from ollama._types import ChatResponse, Message

import opik
from opik.config import OPIK_PROJECT_DEFAULT_NAME
from opik.integrations.ollama import (
    chat_chunks_aggregator,
    stream_wrappers,
    track_ollama,
)

from ...testlib import (
    ANY_BUT_NONE,
    ANY_DICT,
    SpanModel,
    TraceModel,
    assert_equal,
)

MODEL = "llama3.2"


def _response(content="Blue, due to Rayleigh scattering.", done=True, **overrides):
    payload = {
        "model": MODEL,
        "created_at": "2026-01-01T00:00:00Z",
        "done": done,
        "done_reason": "stop" if done else None,
        "message": Message(role="assistant", content=content),
        "prompt_eval_count": 10,
        "eval_count": 8,
        "total_duration": 1_000_000,
        "load_duration": 100_000,
        "prompt_eval_duration": 200_000,
        "eval_duration": 700_000,
    }
    payload.update(overrides)
    return ChatResponse(**payload)


def _chunk(content="", done=False, **overrides):
    payload = {
        "model": MODEL,
        "created_at": "2026-01-01T00:00:00Z",
        "done": done,
        "message": Message(role="assistant", content=content),
    }
    if done:
        payload.update(
            {
                "done_reason": "stop",
                "prompt_eval_count": 10,
                "eval_count": 8,
                "total_duration": 1_000_000,
                "eval_duration": 700_000,
            }
        )
    payload.update(overrides)
    return ChatResponse(**payload)


@pytest.mark.parametrize(
    "project_name, expected_project_name",
    [
        (None, OPIK_PROJECT_DEFAULT_NAME),
        ("ollama-integration-test", "ollama-integration-test"),
    ],
)
def test_ollama_chat__happyflow(
    fake_backend, monkeypatch, project_name, expected_project_name
):
    client = ollama.Client()
    wrapped = track_ollama(client, project_name=project_name)
    monkeypatch.setattr(client, "_request", lambda *a, **kw: _response())

    messages = [{"role": "user", "content": "Why is the sky blue?"}]
    response = wrapped.chat(model=MODEL, messages=messages)

    opik.flush_tracker()

    assert response.message.content == "Blue, due to Rayleigh scattering."

    expected_output = {"message": response.model_dump(mode="json")["message"]}

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="chat",
        input={"messages": messages},
        output=expected_output,
        tags=["ollama"],
        metadata=ANY_DICT,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        project_name=expected_project_name,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="chat",
                input={"messages": messages},
                output=expected_output,
                tags=["ollama"],
                metadata=ANY_DICT,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                project_name=expected_project_name,
                type="llm",
                usage=ANY_DICT,
                model=MODEL,
                provider="ollama",
                spans=[],
                source="sdk",
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1
    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_ollama_chat__usage_mapped_from_ollama_counters(fake_backend, monkeypatch):
    """Ollama reports prompt_eval_count / eval_count, not an OpenAI usage object."""
    client = ollama.Client()
    wrapped = track_ollama(client)
    monkeypatch.setattr(client, "_request", lambda *a, **kw: _response())

    wrapped.chat(model=MODEL, messages=[{"role": "user", "content": "hi"}])
    opik.flush_tracker()

    usage = fake_backend.trace_trees[0].spans[0].usage
    assert usage["prompt_tokens"] == 10
    assert usage["completion_tokens"] == 8
    assert usage["total_tokens"] == 18
    # the native counters survive rather than being dropped
    assert usage["original_usage.eval_duration"] == 700_000


def test_ollama_chat__async__happyflow(fake_backend, monkeypatch):
    client = ollama.AsyncClient()
    wrapped = track_ollama(client)

    async def _request(*args, **kwargs):
        return _response()

    monkeypatch.setattr(client, "_request", _request)

    async def _run():
        return await wrapped.chat(
            model=MODEL, messages=[{"role": "user", "content": "Why is the sky blue?"}]
        )

    response = asyncio.run(_run())
    opik.flush_tracker()

    assert response.message.content == "Blue, due to Rayleigh scattering."
    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].spans[0].provider == "ollama"


def test_ollama_chat__error__span_records_error_info(fake_backend, monkeypatch):
    client = ollama.Client()
    wrapped = track_ollama(client)

    def _raise(*args, **kwargs):
        raise ollama.ResponseError("model not found")

    monkeypatch.setattr(client, "_request", _raise)

    with pytest.raises(ollama.ResponseError):
        wrapped.chat(model=MODEL, messages=[{"role": "user", "content": "hi"}])

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].error_info is not None


def test_ollama_chat__stream__aggregated_into_one_span(fake_backend, monkeypatch):
    client = ollama.Client()
    wrapped = track_ollama(client)

    chunks = [
        _chunk(content="Blue, "),
        _chunk(content="due to "),
        _chunk(content="Rayleigh scattering."),
        _chunk(content="", done=True),
    ]
    monkeypatch.setattr(client, "_request", lambda *a, **kw: iter(chunks))

    received = list(
        wrapped.chat(
            model=MODEL,
            messages=[{"role": "user", "content": "Why is the sky blue?"}],
            stream=True,
        )
    )
    opik.flush_tracker()

    assert len(received) == 4

    assert len(fake_backend.trace_trees) == 1
    span = fake_backend.trace_trees[0].spans[0]
    assert span.name == "chat_stream"
    assert span.output["message"]["content"] == "Blue, due to Rayleigh scattering."
    assert span.usage["prompt_tokens"] == 10


def test_ollama_chat__stream__two_tracked_clients__each_keeps_its_own_provider(
    fake_backend, monkeypatch
):
    """The wrapper closes over its own callback, so nothing is shared between calls.

    Ollama returns a bare generator rather than a stream class, so there is no
    class-level patching here and no state to leak between concurrent streams.
    """
    first = track_ollama(ollama.Client(), provider="provider-one")
    second = track_ollama(ollama.Client(), provider="provider-two")

    chunks = [_chunk(content="hi"), _chunk(content="", done=True)]
    monkeypatch.setattr(first, "_request", lambda *a, **kw: iter(list(chunks)))
    monkeypatch.setattr(second, "_request", lambda *a, **kw: iter(list(chunks)))

    messages = [{"role": "user", "content": "hi"}]
    stream_one = first.chat(model=MODEL, messages=messages, stream=True)
    stream_two = second.chat(model=MODEL, messages=messages, stream=True)

    # drain in reverse order
    list(stream_two)
    list(stream_one)
    opik.flush_tracker()

    providers = sorted(
        span.provider for trace in fake_backend.trace_trees for span in trace.spans
    )
    assert providers == ["provider-one", "provider-two"]


def test_ollama_chat__stream__mid_stream_failure__span_records_error(
    fake_backend, monkeypatch
):
    client = ollama.Client()
    wrapped = track_ollama(client)

    def _failing(*args, **kwargs):
        def _gen():
            yield _chunk(content="partial")
            raise ollama.ResponseError("connection reset")

        return _gen()

    monkeypatch.setattr(client, "_request", _failing)

    with pytest.raises(ollama.ResponseError):
        list(
            wrapped.chat(
                model=MODEL,
                messages=[{"role": "user", "content": "hi"}],
                stream=True,
            )
        )

    opik.flush_tracker()

    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].error_info is not None


def test_aggregate__thinking_deltas__kept_alongside_content():
    """Ollama streams a reasoning model's chain of thought in its own field."""
    aggregated = chat_chunks_aggregator.aggregate(
        [
            _chunk(message=Message(role="assistant", thinking="Light ")),
            _chunk(message=Message(role="assistant", thinking="scatters.")),
            _chunk(content="Blue."),
            _chunk(content="", done=True),
        ]
    )

    assert aggregated.message.content == "Blue."
    assert aggregated.message.thinking == "Light scatters."


def test_aggregate__empty_items__returns_none():
    assert chat_chunks_aggregator.aggregate([]) is None


class _LegacyMessage:
    """Stand-in for ollama < 0.5.0, whose Message has no `thinking` field."""

    def __init__(self, content):
        self.role = "assistant"
        self.content = content
        self.tool_calls = None


class _LegacyChunk:
    """A chunk whose message predates the `thinking` field."""

    def __init__(self, content, done=False):
        self.message = _LegacyMessage(content)
        self.done = done

    def model_dump(self):
        return {
            "model": MODEL,
            "done": self.done,
            "message": {"role": "assistant", "content": self.message.content},
        }


def test_aggregate__message_without_thinking_field__still_aggregates():
    """`thinking` arrived in ollama 0.5.0; our declared floor is 0.4.0.

    Reading it unconditionally raises AttributeError, which the broad handler in
    `aggregate` swallows -- so on 0.4.x every streamed call would silently log
    no output at all.
    """
    aggregated = chat_chunks_aggregator.aggregate(
        [
            _LegacyChunk("Blue."),
            _LegacyChunk(" Scattering.", done=True),
        ]
    )

    assert aggregated is not None
    assert aggregated.message.content == "Blue. Scattering."


def test_wrap_sync_stream__consumer_abandons_stream__not_recorded_as_success():
    """A truncated stream must not be finalized as a completed generation."""
    finalized = []

    def callback(
        output,
        error_info,
        capture_output,
        generators_span_to_end,
        generators_trace_to_end,
    ):
        finalized.append((output, error_info))

    wrapped = stream_wrappers.wrap_sync_stream(
        stream=iter(["a", "b", "c"]),
        span_to_end="SPAN",
        trace_to_end=None,
        generations_aggregator=list,
        finally_callback=callback,
    )

    for item in wrapped:
        break  # consumer abandons after the first chunk
    wrapped.close()

    assert len(finalized) == 1
    output, error_info = finalized[0]
    assert output is None, "partial stream must not be reported as output"
    assert error_info is not None
    assert error_info["exception_type"] == "GeneratorExit"


def test_wrap_async_stream__cancelled__not_recorded_as_success():
    """asyncio.CancelledError is BaseException, so `except Exception` misses it."""
    finalized = []

    def callback(
        output,
        error_info,
        capture_output,
        generators_span_to_end,
        generators_trace_to_end,
    ):
        finalized.append((output, error_info))

    async def source():
        yield "a"
        raise asyncio.CancelledError()

    async def drive():
        wrapped = stream_wrappers.wrap_async_stream(
            stream=source(),
            span_to_end="SPAN",
            trace_to_end=None,
            generations_aggregator=list,
            finally_callback=callback,
        )
        with pytest.raises(asyncio.CancelledError):
            async for _ in wrapped:
                pass

    asyncio.run(drive())

    assert len(finalized) == 1
    output, error_info = finalized[0]
    assert output is None
    assert error_info is not None
    assert error_info["exception_type"] == "CancelledError"


def test_wrap_sync_stream__completed_normally__records_aggregate():
    """The guard must not break the happy path."""
    finalized = []

    def callback(
        output,
        error_info,
        capture_output,
        generators_span_to_end,
        generators_trace_to_end,
    ):
        finalized.append((output, error_info))

    wrapped = stream_wrappers.wrap_sync_stream(
        stream=iter(["a", "b"]),
        span_to_end="SPAN",
        trace_to_end=None,
        generations_aggregator=list,
        finally_callback=callback,
    )
    assert list(wrapped) == ["a", "b"]

    assert finalized == [(["a", "b"], None)]
