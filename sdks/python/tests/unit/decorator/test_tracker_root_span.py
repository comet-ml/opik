import asyncio
import threading
from unittest import mock

import pytest

from opik import context_storage, opik_context
from opik.api_objects import opik_client, trace
from opik.decorator import tracker
from ...testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_track__disable_root_span__no_root_span_created__happy_flow(
    fake_backend, capture_log_check_errors
):
    """Test that the root span is not created when create_root_span=False."""

    @tracker.track
    def f_inner(x):
        return "inner-output"

    @tracker.track(create_duplicate_root_span=False)
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "inner-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_inner",
                input={"x": "inner-input"},
                output={"output": "inner-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__disable_root_span__at_nested_span__all_spans_created(
    fake_backend, capture_log_check_errors
):
    """Test that the nested span is created even if marked with create_duplicate_root_span=False."""

    @tracker.track(create_duplicate_root_span=False)
    def f_inner(x):
        return "inner-output"

    @tracker.track
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_inner",
                        input={"x": "inner-input"},
                        output={"output": "inner-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__disable_root_span__distributed_tracing_with_headers__root_span_created(
    fake_backend, capture_log_check_errors
):
    """Test that the root span is created when distributed tracing is enabled even if create_duplicate_root_span=False."""

    @tracker.track(create_duplicate_root_span=False)
    def f_remote(y, thread_id):
        return f"f-remote-output-from-{thread_id}"

    def distributed_node_runner(y, thread_id, opik_headers):
        f_remote(y, thread_id, opik_distributed_trace_headers=opik_headers)
        return "result-from-node-runner"

    @tracker.track
    def f_outer(x):
        distributed_trace_headers = opik_context.get_distributed_trace_headers()

        t1 = threading.Thread(
            target=distributed_node_runner,
            args=("remote-input-1", "thread-1", distributed_trace_headers),
        )
        t1.start()
        t1.join()
        return "outer-output"

    f_outer("outer-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "outer-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_outer",
                input={"x": "outer-input"},
                output={"output": "outer-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="f_remote",
                        input={"y": "remote-input-1", "thread_id": "thread-1"},
                        output={"output": "f-remote-output-from-thread-1"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


def test_track__disable_root_span__trace_already_created_by_hand__root_span_created(
    fake_backend, capture_log_check_errors
):
    """Test that the root span is created anyway when create_duplicate_root_span=False when trace was created manually."""

    @tracker.track
    def nested_function(x):
        return "nested-output"

    @tracker.track(create_duplicate_root_span=False)
    def top_function(x):
        nested_function(x)
        return "top-output"

    client = opik_client.get_client_cached()
    trace_data = trace.TraceData(
        id="manually-created-trace-id",
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
    )
    context_storage.set_trace_data(trace_data)

    top_function("top-input")

    context_storage.pop_trace_data()

    # Send a create-trace message manually
    client.trace(
        id="manually-created-trace-id",
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
    )

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="manually-created-trace",
        input={"input": "input-of-manually-created-trace"},
        output={"output": "output-of-manually-created-trace"},
        start_time=mock.ANY,  # not ANY_BUT_NONE because we created span manually in the test
        end_time=mock.ANY,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="top_function",
                input={"x": "top-input"},
                output={"output": "top-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="nested_function",
                        input={"x": "top-input"},
                        output={"output": "nested-output"},
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.asyncio
async def test_track__disable_root_span__async_function__no_root_span_created__happy_flow(
    fake_backend, capture_log_check_errors
):
    """Test that the root span is not created when create_duplicate_root_span=False in an async function."""

    @tracker.track
    async def async_f_inner(y):
        await asyncio.sleep(0.01)
        return "inner-output"

    @tracker.track(create_duplicate_root_span=False)
    async def async_f_outer(x):
        await async_f_inner("inner-input")
        return "outer-output"

    await async_f_outer("outer-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_f_outer",
        input={"x": "outer-input"},
        output={"output": "inner-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_f_inner",
                input={"y": "inner-input"},
                output={"output": "inner-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.asyncio
async def test_track__disable_root_span__async_generator__no_root_span_created(
    fake_backend, capture_log_check_errors
):
    """Test that the root span is not created when create_duplicate_root_span=False in an async generator."""

    @tracker.track
    async def some_async_work():
        await asyncio.sleep(0.001)

    @tracker.track
    async def async_generator(y):
        await some_async_work()

        for item in ["yielded-1", " yielded-2", " yielded-3"]:
            yield item

    @tracker.track(create_duplicate_root_span=False)
    async def async_generator_user(x):
        async for _ in async_generator("generator-input"):
            pass

        return "generator-user-output"

    await async_generator_user("generator-user-input")

    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="async_generator_user",
        input={"x": "generator-user-input"},
        output={"output": "generator-user-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="async_generator",
                input={"y": "generator-input"},
                output={"output": "yielded-1 yielded-2 yielded-3"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        name="some_async_work",
                        input={},
                        output=None,
                        start_time=ANY_BUT_NONE,
                        end_time=ANY_BUT_NONE,
                        spans=[],
                    )
                ],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])


@pytest.mark.parametrize("span_type", ["llm", "tool"])
def test_track__disable_root_span__no_root_span_created__show_warning_for_type(
    span_type, fake_backend, capture_log_check_errors
):
    """Test that the root span is not created when create_root_span=False."""

    @tracker.track
    def f_inner(x):
        return "inner-output"

    @tracker.track(create_duplicate_root_span=False, type=span_type)
    def f_outer(x):
        f_inner("inner-input")
        return "outer-output"

    f_outer("outer-input")
    tracker.flush_tracker()

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        name="f_outer",
        input={"x": "outer-input"},
        output={"output": "inner-output"},
        start_time=ANY_BUT_NONE,
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                name="f_inner",
                input={"x": "inner-input"},
                output={"output": "inner-output"},
                start_time=ANY_BUT_NONE,
                end_time=ANY_BUT_NONE,
                spans=[],
            )
        ],
    )

    assert len(fake_backend.trace_trees) == 1

    assert_equal(EXPECTED_TRACE_TREE, fake_backend.trace_trees[0])

    # check that the warning is logged
    message = "The root span 'f_outer' of type '%s' will not be created because its creation was explicitly disabled along with the root trace."
    assert message % span_type in capture_log_check_errors.text
