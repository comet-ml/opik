from dataclasses import dataclass
import json
import time
from typing import Callable
from unittest import mock

import pytest

import anthropic
import httpx2 as httpx

import opik.integrations.anthropic.stream_patchers as sp


@dataclass(frozen=True)
class WrapperConfig:
    """Describes one of the six stream wrappers in stream_patchers.py."""

    id: str
    patch_fn: Callable
    global_name: str
    stream_cls: type
    patch_arg_cls: type
    needs_get_final_message: bool


def _sync_wrappers() -> list[WrapperConfig]:
    wrappers = [
        WrapperConfig(
            id="Stream",
            patch_fn=sp.patch_sync_stream,
            global_name="original_stream_iter_method",
            stream_cls=anthropic.Stream,
            patch_arg_cls=anthropic.Stream,
            needs_get_final_message=False,
        ),
        WrapperConfig(
            id="MessageStream",
            patch_fn=sp.patch_sync_message_stream_manager,
            global_name="original_message_stream_iter_method",
            stream_cls=anthropic.MessageStream,
            patch_arg_cls=anthropic.MessageStreamManager,
            needs_get_final_message=True,
        ),
    ]
    if sp.BetaMessageStream is not None:
        wrappers.append(
            WrapperConfig(
                id="BetaMessageStream",
                patch_fn=sp.patch_sync_beta_message_stream_manager,
                global_name="original_beta_message_stream_iter_method",
                stream_cls=sp.BetaMessageStream,
                patch_arg_cls=sp.BetaMessageStreamManager,
                needs_get_final_message=True,
            )
        )
    return wrappers


def _async_wrappers() -> list[WrapperConfig]:
    wrappers = [
        WrapperConfig(
            id="AsyncStream",
            patch_fn=sp.patch_async_stream,
            global_name="original_async_stream_aiter_method",
            stream_cls=anthropic.AsyncStream,
            patch_arg_cls=anthropic.AsyncStream,
            needs_get_final_message=False,
        ),
        WrapperConfig(
            id="AsyncMessageStream",
            patch_fn=sp.patch_async_message_stream_manager,
            global_name="original_async_message_stream_aiter_method",
            stream_cls=anthropic.AsyncMessageStream,
            patch_arg_cls=anthropic.AsyncMessageStreamManager,
            needs_get_final_message=True,
        ),
    ]
    if sp.BetaAsyncMessageStream is not None:
        wrappers.append(
            WrapperConfig(
                id="BetaAsyncMessageStream",
                patch_fn=sp.patch_async_beta_message_stream_manager,
                global_name="original_beta_async_message_stream_aiter_method",
                stream_cls=sp.BetaAsyncMessageStream,
                patch_arg_cls=sp.BetaAsyncMessageStreamManager,
                needs_get_final_message=True,
            )
        )
    return wrappers


def _raising_iter(self):
    raise RuntimeError("stream-blew-up")
    yield  # make this a generator function


async def _raising_aiter(self):
    raise RuntimeError("stream-blew-up")
    yield  # make this an async generator function


def _assert_error_info_matches(error_info):
    """Assert the callback's error_info reflects the injected RuntimeError.

    Guards against a wrapper reporting incorrect exception metadata (wrong
    type, dropped message, missing traceback) while still passing.
    """
    assert error_info is not None
    assert error_info["exception_type"] == "RuntimeError"
    assert error_info["message"] == "stream-blew-up"
    assert "test_stream_patchers.py" in error_info["traceback"]


@pytest.fixture
def restore_stream_patches():
    """Save and restore all class-level dunder methods and module globals
    that the stream patchers modify, so patches never leak across tests."""
    classes = [
        anthropic.Stream,
        anthropic.AsyncStream,
        anthropic.MessageStream,
        anthropic.AsyncMessageStream,
        anthropic.MessageStreamManager,
        anthropic.AsyncMessageStreamManager,
    ]
    if sp.BetaMessageStream is not None:
        classes += [
            sp.BetaMessageStream,
            sp.BetaAsyncMessageStream,
            sp.BetaMessageStreamManager,
            sp.BetaAsyncMessageStreamManager,
        ]

    saved_methods = {}
    for cls in classes:
        for name in ("__iter__", "__aiter__", "__enter__", "__aenter__"):
            if hasattr(cls, name):
                saved_methods[(cls, name)] = getattr(cls, name)

    saved_globals = {k: getattr(sp, k) for k in dir(sp) if k.startswith("original_")}

    yield

    for (cls, name), method in saved_methods.items():
        setattr(cls, name, method)
    for key, value in saved_globals.items():
        setattr(sp, key, value)


def _install(config: WrapperConfig, raising_fn: Callable, callback: mock.Mock):
    """Install a stream patcher's class-level override backed by raising_fn."""
    setattr(sp, config.global_name, raising_fn)
    throwaway = object.__new__(config.patch_arg_cls)
    config.patch_fn(
        throwaway,
        span_to_end=None,
        trace_to_end=None,
        finally_callback=callback,
    )


def _make_stream(config: WrapperConfig, tracked: bool, is_async: bool = False):
    stream = object.__new__(config.stream_cls)
    if tracked:
        stream.opik_tracked_instance = True
        stream.span_to_end = None
        stream.trace_to_end = None
        if config.needs_get_final_message:
            if is_async:

                async def _gfm():
                    return None

                stream.get_final_message = _gfm
            else:
                stream.get_final_message = lambda: None
    return stream


@pytest.mark.parametrize("config", _sync_wrappers(), ids=lambda c: c.id)
def test_sync_non_tracked_exception_propagates(restore_stream_patches, config):
    """Regression test for the `return` inside `finally` bug.

    Once a stream patcher installs its class-level __iter__ override, a
    non-tracked stream whose iteration raises must propagate the exception
    (the old `return` in `finally` silently swallowed it). The cleanup
    callback must not run for a stream opik never tracked.
    """
    callback = mock.Mock()
    _install(config, _raising_iter, callback)
    stream = _make_stream(config, tracked=False)

    with pytest.raises(RuntimeError, match="stream-blew-up"):
        for _ in stream:
            pass

    callback.assert_not_called()


@pytest.mark.parametrize("config", _sync_wrappers(), ids=lambda c: c.id)
def test_sync_tracked_exception_propagates_and_callback_runs(
    restore_stream_patches, config
):
    """A tracked stream that errors must propagate the exception AND run the
    span-closing callback exactly once with error_info set.
    """
    callback = mock.Mock()
    _install(config, _raising_iter, callback)
    stream = _make_stream(config, tracked=True)

    with pytest.raises(RuntimeError, match="stream-blew-up"):
        for _ in stream:
            pass

    callback.assert_called_once()
    _, kwargs = callback.call_args
    assert kwargs["capture_output"] is True
    _assert_error_info_matches(kwargs["error_info"])


@pytest.mark.parametrize("config", _async_wrappers(), ids=lambda c: c.id)
@pytest.mark.asyncio
async def test_async_non_tracked_exception_propagates(restore_stream_patches, config):
    """Async variant of the regression test — non-tracked async stream
    exceptions must propagate, cleanup callback must not run.
    """
    callback = mock.Mock()
    _install(config, _raising_aiter, callback)
    stream = _make_stream(config, tracked=False, is_async=True)

    with pytest.raises(RuntimeError, match="stream-blew-up"):
        async for _ in stream:
            pass

    callback.assert_not_called()


@pytest.mark.parametrize("config", _async_wrappers(), ids=lambda c: c.id)
@pytest.mark.asyncio
async def test_async_tracked_exception_propagates_and_callback_runs(
    restore_stream_patches, config
):
    """Async variant — tracked stream exceptions must propagate AND run the
    span-closing callback exactly once with error_info set.
    """
    callback = mock.Mock()
    _install(config, _raising_aiter, callback)
    stream = _make_stream(config, tracked=True, is_async=True)

    with pytest.raises(RuntimeError, match="stream-blew-up"):
        async for _ in stream:
            pass

    callback.assert_called_once()
    _, kwargs = callback.call_args
    assert kwargs["capture_output"] is True
    _assert_error_info_matches(kwargs["error_info"])


def _raw_events() -> list:
    """A minimal but complete raw event sequence, including tool input deltas
    that anthropic accumulates into partial JSON."""
    message = anthropic.types.Message.construct(
        id="msg_1",
        type="message",
        role="assistant",
        model="claude-3-5-sonnet-20241022",
        content=[],
        stop_reason=None,
        stop_sequence=None,
        usage={"input_tokens": 10, "output_tokens": 0},
    )
    types = anthropic.types

    return [
        types.RawMessageStartEvent.construct(type="message_start", message=message),
        types.RawContentBlockStartEvent.construct(
            type="content_block_start",
            index=0,
            content_block=types.TextBlock.construct(type="text", text=""),
        ),
        types.RawContentBlockDeltaEvent.construct(
            type="content_block_delta",
            index=0,
            delta=types.TextDelta.construct(type="text_delta", text="Hello"),
        ),
        types.RawContentBlockStopEvent.construct(type="content_block_stop", index=0),
        types.RawContentBlockStartEvent.construct(
            type="content_block_start",
            index=1,
            content_block=types.ToolUseBlock.construct(
                type="tool_use", id="tool_1", name="get_weather", input={}
            ),
        ),
        types.RawContentBlockDeltaEvent.construct(
            type="content_block_delta",
            index=1,
            delta=types.InputJSONDelta.construct(
                type="input_json_delta", partial_json='{"city": '
            ),
        ),
        types.RawContentBlockDeltaEvent.construct(
            type="content_block_delta",
            index=1,
            delta=types.InputJSONDelta.construct(
                type="input_json_delta", partial_json='"Paris"}'
            ),
        ),
        types.RawContentBlockStopEvent.construct(type="content_block_stop", index=1),
        types.RawMessageStopEvent.construct(type="message_stop"),
    ]


def _assert_accumulated_message_matches(output):
    assert output is not None
    assert output.content[0].text == "Hello"
    assert output.content[1].input == {"city": "Paris"}


def test_sync_stream_accumulates_events_into_the_final_message(
    restore_stream_patches,
):
    """Regression test for anthropic changing `accumulate_event()`'s signature
    (1.5.0 made the partial-JSON buffer a required caller-owned argument).
    """
    events = _raw_events()

    def _iter_events(self):
        yield from events

    callback = mock.Mock()
    config = next(c for c in _sync_wrappers() if c.id == "Stream")
    _install(config, _iter_events, callback)
    stream = _make_stream(config, tracked=True)

    assert list(stream) == events

    callback.assert_called_once()
    _, kwargs = callback.call_args
    assert kwargs["error_info"] is None
    _assert_accumulated_message_matches(kwargs["output"])


@pytest.mark.asyncio
async def test_async_stream_accumulates_events_into_the_final_message(
    restore_stream_patches,
):
    """Async variant of the `accumulate_event()` signature regression test."""
    events = _raw_events()

    async def _aiter_events(self):
        for event in events:
            yield event

    callback = mock.Mock()
    config = next(c for c in _async_wrappers() if c.id == "AsyncStream")
    _install(config, _aiter_events, callback)
    stream = _make_stream(config, tracked=True, is_async=True)

    assert [event async for event in stream] == events

    callback.assert_called_once()
    _, kwargs = callback.call_args
    assert kwargs["error_info"] is None
    _assert_accumulated_message_matches(kwargs["output"])


@pytest.mark.parametrize(
    "config",
    [config for config in _sync_wrappers() if config.needs_get_final_message],
    ids=lambda config: config.id,
)
def test_sync_message_stream_early_exit_uses_current_snapshot(
    restore_stream_patches, config
):
    partial_snapshot = object()
    complete_snapshot = object()

    def _iter_events(self):
        yield "first"
        yield "second"

    def _drain_remaining_events():
        return complete_snapshot

    callback = mock.Mock()
    _install(config, _iter_events, callback)
    stream = _make_stream(config, tracked=True)
    stream.get_final_message = mock.Mock(side_effect=_drain_remaining_events)

    with mock.patch.object(
        config.stream_cls,
        "current_message_snapshot",
        new_callable=mock.PropertyMock,
        return_value=partial_snapshot,
    ):
        iterator = iter(stream)
        assert next(iterator) == "first"
        iterator.close()

    stream.get_final_message.assert_not_called()
    callback.assert_called_once()
    assert callback.call_args.kwargs["output"] is partial_snapshot


@pytest.mark.parametrize(
    "config",
    [config for config in _async_wrappers() if config.needs_get_final_message],
    ids=lambda config: config.id,
)
@pytest.mark.asyncio
async def test_async_message_stream_early_exit_uses_current_snapshot(
    restore_stream_patches, config
):
    partial_snapshot = object()
    complete_snapshot = object()

    async def _aiter_events(self):
        yield "first"
        yield "second"

    async def _drain_remaining_events():
        return complete_snapshot

    callback = mock.Mock()
    _install(config, _aiter_events, callback)
    stream = _make_stream(config, tracked=True, is_async=True)
    stream.get_final_message = mock.AsyncMock(side_effect=_drain_remaining_events)

    with mock.patch.object(
        config.stream_cls,
        "current_message_snapshot",
        new_callable=mock.PropertyMock,
        return_value=partial_snapshot,
    ):
        iterator = stream.__aiter__()
        assert await anext(iterator) == "first"
        await iterator.aclose()

    stream.get_final_message.assert_not_awaited()
    callback.assert_called_once()
    assert callback.call_args.kwargs["output"] is partial_snapshot


@pytest.mark.parametrize(
    "config",
    [config for config in _sync_wrappers() if config.needs_get_final_message],
    ids=lambda config: config.id,
)
def test_sync_empty_message_stream_closes_callback_without_snapshot(
    restore_stream_patches, config
):
    def _iter_events(self):
        yield from ()

    callback = mock.Mock()
    _install(config, _iter_events, callback)
    stream = _make_stream(config, tracked=True)
    stream.get_final_message = mock.Mock(
        side_effect=AssertionError("message snapshot is not initialized")
    )
    snapshot = mock.PropertyMock(
        side_effect=AssertionError("message snapshot is not initialized")
    )

    with mock.patch.object(config.stream_cls, "current_message_snapshot", snapshot):
        assert list(stream) == []

    stream.get_final_message.assert_not_called()
    snapshot.assert_not_called()
    callback.assert_called_once()
    assert callback.call_args.kwargs["output"] is None


@pytest.mark.parametrize(
    "config",
    [config for config in _async_wrappers() if config.needs_get_final_message],
    ids=lambda config: config.id,
)
@pytest.mark.asyncio
async def test_async_empty_message_stream_closes_callback_without_snapshot(
    restore_stream_patches, config
):
    async def _aiter_events(self):
        for _ in ():
            yield None

    callback = mock.Mock()
    _install(config, _aiter_events, callback)
    stream = _make_stream(config, tracked=True, is_async=True)
    stream.get_final_message = mock.AsyncMock(
        side_effect=AssertionError("message snapshot is not initialized")
    )
    snapshot = mock.PropertyMock(
        side_effect=AssertionError("message snapshot is not initialized")
    )

    with mock.patch.object(config.stream_cls, "current_message_snapshot", snapshot):
        assert [event async for event in stream] == []

    stream.get_final_message.assert_not_awaited()
    snapshot.assert_not_called()
    callback.assert_called_once()
    assert callback.call_args.kwargs["output"] is None


def test_sync_message_stream_early_exit_does_not_read_unread_sse_events(
    restore_stream_patches,
):
    pulled_deltas = []

    def _event_frame(event):
        return f"event: {event['type']}\ndata: {json.dumps(event)}\n\n".encode()

    def _response_body():
        yield _event_frame(
            {
                "type": "message_start",
                "message": {
                    "id": "msg_1",
                    "type": "message",
                    "role": "assistant",
                    "model": "test-model",
                    "content": [],
                    "stop_reason": None,
                    "stop_sequence": None,
                    "usage": {"input_tokens": 1, "output_tokens": 0},
                },
            }
        )
        yield _event_frame(
            {
                "type": "content_block_start",
                "index": 0,
                "content_block": {"type": "text", "text": ""},
            }
        )
        for index in range(4):
            time.sleep(0.01)
            pulled_deltas.append(index)
            yield _event_frame(
                {
                    "type": "content_block_delta",
                    "index": 0,
                    "delta": {"type": "text_delta", "text": f"part-{index}"},
                }
            )

    client = anthropic.Anthropic(
        api_key="test",
        base_url="http://mock",
        http_client=httpx.Client(
            transport=httpx.MockTransport(
                lambda request: httpx.Response(
                    200,
                    headers={"content-type": "text/event-stream"},
                    content=_response_body(),
                )
            )
        ),
    )
    callback = mock.Mock()
    stream_manager = client.messages.stream(
        model="test-model",
        max_tokens=16,
        messages=[{"role": "user", "content": "hello"}],
    )
    sp.patch_sync_message_stream_manager(
        stream_manager,
        span_to_end=None,
        trace_to_end=None,
        finally_callback=callback,
    )

    try:
        with stream_manager as stream:
            iterator = iter(stream)
            assert next(iterator).type == "message_start"
            iterator.close()
    finally:
        client.close()

    assert pulled_deltas == []
    callback.assert_called_once()
    output = callback.call_args.kwargs["output"]
    assert output.id == "msg_1"
    assert output.content == []
