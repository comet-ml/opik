"""`@track(flush=True)` must flush when a tracked generator finishes.

A plain tracked function calls `client.flush()` when it returns. A tracked
generator ends its span from `generator_wrappers`, so the flag has to travel
with the wrapper; when it does not, whatever the generator logged stays in the
batcher and is only sent by the size/interval trigger or an explicit flush.

The tests that read `fake_backend.trace_trees` rely on the same property as the
existing `flush=True` tests for the context managers: batching is enabled in the
test harness, so a trace that is visible right after the call is visible because
it was flushed, not because of a background trigger.
"""

import asyncio
from unittest import mock

import pytest

from opik.api_objects import opik_client
from opik.decorator import tracker
from ...testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    SpanModel,
    TraceModel,
    assert_equal,
)


def _expected_generator_trace(output, error_info=None) -> TraceModel:
    return TraceModel(
        id=ANY_BUT_NONE,
        name="f",
        input={"x": "generator-input"},
        output=output,
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        error_info=error_info,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f",
                input={"x": "generator-input"},
                output=output,
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
                error_info=error_info,
                source="sdk",
            )
        ],
        source="sdk",
    )


def test_track__flush_true__generator__logged_data_sent_without_explicit_flush(
    fake_backend,
):
    @tracker.track(flush=True)
    def f(x):
        yield "yielded-1"
        yield " yielded-2"

    for _ in f("generator-input"):
        pass

    # No `tracker.flush_tracker()` here: `flush=True` is what must send the data.
    assert len(fake_backend.trace_trees) == 1

    assert_equal(
        _expected_generator_trace(output={"output": "yielded-1 yielded-2"}),
        fake_backend.trace_trees[0],
    )


@pytest.mark.asyncio
async def test_track__flush_true__async_generator__logged_data_sent_without_explicit_flush(
    fake_backend,
):
    @tracker.track(flush=True)
    async def f(x):
        await asyncio.sleep(0)
        yield "yielded-1"
        yield " yielded-2"

    async for _ in f("generator-input"):
        pass

    assert len(fake_backend.trace_trees) == 1

    assert_equal(
        _expected_generator_trace(output={"output": "yielded-1 yielded-2"}),
        fake_backend.trace_trees[0],
    )


def test_track__flush_true__generator_raises__logged_data_sent_without_explicit_flush(
    fake_backend,
):
    @tracker.track(flush=True)
    def f(x):
        raise Exception("error message")
        yield

    with pytest.raises(Exception):
        for _ in f("generator-input"):
            pass

    assert len(fake_backend.trace_trees) == 1

    assert_equal(
        _expected_generator_trace(
            output=None,
            error_info={
                "exception_type": "Exception",
                "message": "error message",
                "traceback": ANY_STRING,
            },
        ),
        fake_backend.trace_trees[0],
    )


def test_track__generator__flush_matches_the_flag(fake_backend):
    """Pin the flush itself, so the delivery assertions cannot go vacuous."""

    @tracker.track(flush=True)
    def flushing_generator():
        yield "value"

    @tracker.track
    def non_flushing_generator():
        yield "value"

    client = opik_client.get_global_client()

    with mock.patch.object(client, "flush", wraps=client.flush) as flush_mock:
        for _ in flushing_generator():
            pass

        assert flush_mock.call_count == 1

        for _ in non_flushing_generator():
            pass

        assert flush_mock.call_count == 1, "flush must not be called without flush=True"
