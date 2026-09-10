import asyncio

import logging
from unittest import mock

import openai
import pytest

from opik.integrations.openai import response_events_aggregator, stream_patchers


def test_aggregate__no_terminal_event__returns_none_without_error_log(caplog):
    with caplog.at_level(logging.ERROR, logger=response_events_aggregator.__name__):
        assert response_events_aggregator.aggregate([]) is None

    assert caplog.records == []


@pytest.fixture(autouse=True)
def restore_stream_patches():
    sync_iter_method = openai.Stream.__iter__
    async_iter_method = openai.AsyncStream.__aiter__
    sync_original_method = stream_patchers.original_stream_iter_method
    async_original_method = stream_patchers.original_async_stream_aiter_method

    yield

    openai.Stream.__iter__ = sync_iter_method
    openai.AsyncStream.__aiter__ = async_iter_method
    stream_patchers.original_stream_iter_method = sync_original_method
    stream_patchers.original_async_stream_aiter_method = async_original_method


def _partial_stream(_stream):
    yield object()


async def _partial_async_stream(_stream):
    yield object()


def _assert_abandoned_stream_callback(callback):
    callback.assert_called_once_with(
        output=None,
        error_info=None,
        capture_output=True,
        generators_span_to_end=None,
        generators_trace_to_end=None,
    )


def test_patch_sync_stream__abandoned_before_terminal_event__finishes_without_error_log(
    caplog,
):
    stream_patchers.original_stream_iter_method = _partial_stream
    callback = mock.Mock()
    stream = stream_patchers.patch_sync_stream(
        stream=object.__new__(openai.Stream),
        span_to_end=None,
        trace_to_end=None,
        generations_aggregator=response_events_aggregator.aggregate,
        finally_callback=callback,
    )

    with caplog.at_level(logging.ERROR, logger=response_events_aggregator.__name__):
        iterator = iter(stream)
        next(iterator)
        iterator.close()

    _assert_abandoned_stream_callback(callback)
    assert caplog.records == []


@pytest.mark.asyncio
async def test_patch_async_stream__abandoned_before_terminal_event__finishes_without_error_log(
    caplog,
):
    stream_patchers.original_async_stream_aiter_method = _partial_async_stream
    callback = mock.Mock()
    stream = stream_patchers.patch_async_stream(
        stream=object.__new__(openai.AsyncStream),
        span_to_end=None,
        trace_to_end=None,
        generations_aggregator=response_events_aggregator.aggregate,
        finally_callback=callback,
    )

    with caplog.at_level(logging.ERROR, logger=response_events_aggregator.__name__):
        iterator = stream.__aiter__()
        await anext(iterator)
        await iterator.aclose()

    _assert_abandoned_stream_callback(callback)
    assert caplog.records == []


@pytest.mark.asyncio
async def test_patch_async_stream__cancelled_before_terminal_event__finishes_without_error_log(
    caplog,
):
    stream_started = asyncio.Event()

    async def partial_stream_then_wait(_stream):
        yield object()
        stream_started.set()
        await asyncio.Event().wait()

    stream_patchers.original_async_stream_aiter_method = partial_stream_then_wait
    callback = mock.Mock()
    stream = stream_patchers.patch_async_stream(
        stream=object.__new__(openai.AsyncStream),
        span_to_end=None,
        trace_to_end=None,
        generations_aggregator=response_events_aggregator.aggregate,
        finally_callback=callback,
    )

    with caplog.at_level(logging.ERROR, logger=response_events_aggregator.__name__):
        iterator = stream.__aiter__()
        await anext(iterator)
        next_item = asyncio.create_task(anext(iterator))
        await stream_started.wait()
        next_item.cancel()
        with pytest.raises(asyncio.CancelledError):
            await next_item

    _assert_abandoned_stream_callback(callback)
    assert caplog.records == []
