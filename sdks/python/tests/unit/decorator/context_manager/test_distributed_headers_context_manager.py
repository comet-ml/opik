import pytest

import opik
import opik.opik_context
from opik import opik_context, context_storage
from opik.decorator.context_manager import distributed_headers_context_manager
from opik.types import ErrorInfoDict

from tests.testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_distributed_headers__happy_flow(fake_backend):
    """Test that distributed headers context manager creates a root span with provided headers."""
    with opik.start_as_current_trace("parent-trace", flush=True):
        with opik.start_as_current_span("parent-span") as parent_span:
            parent_span.output = {"output": "parent-output"}

            distributed_headers = opik_context.get_distributed_trace_headers()

    with distributed_headers_context_manager.distributed_headers(
        distributed_headers, flush=True
    ):
        # Get and update the root span inside the distributed headers context
        current_span = context_storage.top_span_data()
        current_span.update(
            name="child-span",
            output={"output": "child-output"},
        )

    # Verify trace tree structure
    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="parent-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="parent-span",
                output={"output": "parent-output"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="child-span",
                        output={"output": "child-output"},
                        type="general",
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        last_updated_at=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


@pytest.mark.parametrize("empty_distributed_headers", [None, {}])
def test_distributed_headers__empty_headers_warning(
    empty_distributed_headers, fake_backend, capture_log
):
    """Test that empty header, log a warning, and skip processing."""
    # Test with None
    with distributed_headers_context_manager.distributed_headers(
        empty_distributed_headers
    ):
        pass

    assert "Empty distributed headers provided" in capture_log.text
    assert len(fake_backend.trace_trees) == 0


def test_distributed_headers__exception_handling_and_reraise(fake_backend, capture_log):
    """Test that exceptions in user code are logged and re-raised. Also verify that root span data is saved
    and cleaned up even when an exception occurs."""
    # Create a parent trace with a span to get valid IDs
    with opik.start_as_current_trace("parent-trace", flush=True):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    with pytest.raises(Exception) as exc_info:
        with distributed_headers_context_manager.distributed_headers(
            distributed_headers, flush=True
        ):
            raise Exception("Test error in user code")

    # Exception should be re-raised
    assert str(exc_info.value) == "Test error in user code"

    # Verify context is cleaned up after an exception
    assert opik.opik_context.get_current_span_data() is None

    # Error should be logged
    assert (
        "Error in user's script while executing distributed headers context manager"
        in capture_log.text
    )
    assert "Test error in user code" in capture_log.text

    # Exception should be logged to the backend
    assert len(fake_backend.trace_trees) == 1
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="parent-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="parent-span",
                end_time=ANY_BUT_NONE,
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="root",
                        end_time=ANY_BUT_NONE,
                        error_info=ErrorInfoDict(
                            exception_type="Exception",
                            message="Test error in user code",
                            traceback=ANY_BUT_NONE,
                        ),
                    )
                ],
            )
        ],
    )

    trace_tree = fake_backend.trace_trees[0]
    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)


def test_distributed_headers__context_cleanup_in_normal_flow(fake_backend):
    """Test that span data is properly cleaned up from context after normal execution."""
    # Create a parent trace to get a valid trace ID
    with opik.start_as_current_trace("parent-trace", flush=True):
        with opik.start_as_current_span("parent-span", flush=True):
            distributed_headers = opik_context.get_distributed_trace_headers()

    # Verify context is clean before
    assert opik.opik_context.get_current_span_data() is None

    with distributed_headers_context_manager.distributed_headers(distributed_headers):
        # Verify the root span is in context during execution
        current_span = opik.opik_context.get_current_span_data()
        assert current_span is not None
        assert current_span.name == "root"

    # Verify context is cleaned up after a normal exit
    assert opik.opik_context.get_current_span_data() is None

    # Verify span was saved to the backend
    assert len(fake_backend.trace_trees) == 1


def test_distributed_headers__nested_spans(fake_backend):
    """Test that spans created inside the distributed headers context are properly nested."""
    # Create parent trace with span to get valid IDs
    with opik.start_as_current_trace("parent-trace", flush=True):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    with distributed_headers_context_manager.distributed_headers(
        distributed_headers, flush=True
    ):
        # Create nested spans
        with opik.start_as_current_span("span-1", flush=True) as span1:
            span1.output = {"level": 1}

            with opik.start_as_current_span("span-2", flush=True) as span2:
                span2.output = {"level": 2}

    # Verify a trace tree
    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="parent-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="parent-span",
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="root",
                        type="general",
                        end_time=ANY_BUT_NONE,
                        project_name="Default Project",
                        spans=[
                            SpanModel(
                                id=ANY_BUT_NONE,
                                start_time=ANY_BUT_NONE,
                                name="span-1",
                                output={"level": 1},
                                type="general",
                                end_time=ANY_BUT_NONE,
                                project_name="Default Project",
                                spans=[
                                    SpanModel(
                                        id=ANY_BUT_NONE,
                                        start_time=ANY_BUT_NONE,
                                        name="span-2",
                                        output={"level": 2},
                                        type="general",
                                        end_time=ANY_BUT_NONE,
                                        project_name="Default Project",
                                        last_updated_at=ANY_BUT_NONE,
                                    )
                                ],
                                last_updated_at=ANY_BUT_NONE,
                            )
                        ],
                        last_updated_at=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    trace_tree = fake_backend.trace_trees[0]

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)
