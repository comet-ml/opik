from dataclasses import dataclass
from typing import Callable
from unittest import mock

import pytest

import anthropic

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
    assert kwargs["error_info"] is not None


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
    assert kwargs["error_info"] is not None
