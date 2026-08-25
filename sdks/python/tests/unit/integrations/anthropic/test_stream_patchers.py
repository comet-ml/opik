import pytest
from unittest import mock

import httpx2
import anthropic

import opik.integrations.anthropic.stream_patchers as stream_patchers


@pytest.fixture
def restore_stream_iter():
    """The stream patchers install a class-level __iter__ override on
    anthropic.Stream. Restore both the class attribute and the module global
    that the decorator captures so the patch never leaks across tests."""
    original_iter = anthropic.Stream.__iter__
    original_captured = stream_patchers.original_stream_iter_method
    try:
        yield
    finally:
        anthropic.Stream.__iter__ = original_iter
        stream_patchers.original_stream_iter_method = original_captured


def _install_patched_iter(finally_callback, underlying_iter):
    """Install opik's patched Stream.__iter__ backed by `underlying_iter`.

    `patch_sync_stream` patches the class globally and marks the stream passed
    to it as tracked. We pass a throwaway so the real stream built later can
    stay untracked, which is the condition that used to trigger the bug.
    """
    stream_patchers.original_stream_iter_method = underlying_iter
    throwaway = mock.Mock(spec=anthropic.Stream)
    stream_patchers.patch_sync_stream(
        throwaway,
        span_to_end=None,
        trace_to_end=None,
        finally_callback=finally_callback,
    )


def _make_stream(tracked: bool) -> anthropic.Stream:
    response = mock.Mock(spec=httpx2.Response)
    client = mock.Mock(spec=anthropic.Anthropic)
    stream = anthropic.Stream(
        cast_to=anthropic.types.Message, response=response, client=client
    )
    if tracked:
        stream.opik_tracked_instance = True
        stream.span_to_end = None
        stream.trace_to_end = None
    return stream


def _raising_iter(self):
    raise RuntimeError("stream-blew-up")
    yield  # make this a generator function


def test_non_tracked_stream_exception_propagates(restore_stream_iter):
    """Regression test for the `return` inside `finally` bug.

    Once the opik class-level Stream.__iter__ patch is installed, a
    non-opik-tracked Stream whose iteration raises used to have its exception
    silently swallowed by the early `return` in the finally block. The
    exception must propagate to the caller, and the opik cleanup callback must
    not run for a stream opik never tracked.
    """
    callback = mock.Mock()
    _install_patched_iter(finally_callback=callback, underlying_iter=_raising_iter)
    stream = _make_stream(tracked=False)

    with pytest.raises(RuntimeError, match="stream-blew-up"):
        for _ in stream:
            pass

    callback.assert_not_called()


def test_tracked_stream_exception_propagates_and_finally_callback_runs(
    restore_stream_iter,
):
    """A tracked Stream that errors must still propagate the exception AND run
    the span-closing finally_callback exactly once (so the span is ended with
    the error info). Guards both the bug fix and the original tracked path."""
    callback = mock.Mock()
    _install_patched_iter(finally_callback=callback, underlying_iter=_raising_iter)
    stream = _make_stream(tracked=True)

    with pytest.raises(RuntimeError, match="stream-blew-up"):
        for _ in stream:
            pass

    callback.assert_called_once()
    _, kwargs = callback.call_args
    assert kwargs["capture_output"] is True
    assert kwargs["error_info"] is not None
