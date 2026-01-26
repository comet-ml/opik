import pprint

import pytest

import opik
import opik.opik_context
from opik import opik_context
from opik.decorator.context_manager import distributed_headers_context_manager
from opik.types import DistributedTraceHeadersDict
from tests.testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_distributed_headers__happy_flow(fake_backend):
    """Test that distributed headers context manager creates a root span with provided headers."""
    with opik.start_as_current_trace("parent-trace"):
        with opik.start_as_current_span("parent-span") as parent_span:
            parent_span.output = {"output": "parent-output"}

            distributed_headers = opik_context.get_distributed_trace_headers()

    @opik.track(name="child-span")
    def llm_application(x):
        return "child-span output"

    with distributed_headers_context_manager.distributed_headers(distributed_headers):
        # call tracked function
        llm_application("child-span input")

    opik.flush_tracker()
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
                        input={"x": "child-span input"},
                        output={"output": "child-span output"},
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


def test_distributed_headers__only_trace__happy_flow(fake_backend):
    """Test that distributed headers context manager creates a root span with provided headers."""
    with opik.start_as_current_trace(name="parent-trace") as current_trace_data:
        distributed_headers = DistributedTraceHeadersDict(
            opik_trace_id=current_trace_data.id, opik_parent_span_id=None
        )

    @opik.track(name="child-span")
    def llm_application(x):
        return "child-span output"

    with distributed_headers_context_manager.distributed_headers(distributed_headers):
        # call tracked function
        llm_application("child-span input")

    opik.flush_tracker()
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
                name="child-span",
                input={"x": "child-span input"},
                output={"output": "child-span output"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    pprint.pp(fake_backend.trace_trees[0])

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


@pytest.mark.parametrize("empty_distributed_headers", [None, {}])
def test_distributed_headers__empty_headers_warning__child_span_created(
    empty_distributed_headers, fake_backend, capture_log
):
    """Test that empty header, log a warning, and child span is created as expected."""

    @opik.track(name="child-span")
    def llm_application(x):
        return "child-span output"

    with distributed_headers_context_manager.distributed_headers(
        empty_distributed_headers
    ):
        llm_application("child-span input")

    opik.flush_tracker()

    assert "Empty distributed headers provided" in capture_log.text
    assert len(fake_backend.trace_trees) == 1

    # Verify the trace tree structure is correct
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="child-span",
        project_name="Default Project",
        input={"x": "child-span input"},
        output={"output": "child-span output"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="child-span",
                input={"x": "child-span input"},
                output={"output": "child-span output"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="Default Project",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_distributed_headers__exception_handling_and_reraise(fake_backend, capture_log):
    """Test that exceptions in user code are logged and re-raised. Also verify that root span data is saved
    and cleaned up even when an exception occurs."""
    # Create a parent trace with a span to get valid IDs
    with opik.start_as_current_trace("parent-trace", flush=True):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    with pytest.raises(Exception) as exc_info:
        with distributed_headers_context_manager.distributed_headers(
            distributed_headers
        ):
            raise Exception("Test error in user code")

    # Exception should be re-raised
    assert str(exc_info.value) == "Test error in user code"

    # Verify context is cleaned up after an exception
    assert opik.opik_context.get_current_span_data() is None
    assert opik.opik_context.get_current_trace_data() is None

    # Error should be logged
    assert (
        "Error in user's script while executing distributed headers context manager"
        in capture_log.text
    )
    assert "Test error in user code" in capture_log.text


def test_distributed_headers__context_cleanup_in_normal_flow(fake_backend):
    """Test that span data is properly cleaned up from context after normal execution."""
    # Create a parent trace to get a valid trace ID
    with opik.start_as_current_trace("parent-trace"):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    # Verify context is clean before
    assert opik.opik_context.get_current_span_data() is None

    @opik.track(name="child-span")
    def llm_application(x):
        return "child-span output"

    with distributed_headers_context_manager.distributed_headers(
        distributed_headers,
    ):
        llm_application("child-span input")

    opik.flush_tracker()

    # Verify context is cleaned up after a normal exit
    assert opik.opik_context.get_current_span_data() is None
    assert opik.opik_context.get_current_trace_data() is None

    # Verify span was saved to the backend
    assert len(fake_backend.trace_trees) == 1


def test_distributed_headers__nested_spans(fake_backend):
    """Test that spans created inside the distributed headers context are properly nested."""
    # Create parent trace with span to get valid IDs
    with opik.start_as_current_trace("parent-trace"):
        with opik.start_as_current_span("parent-span"):
            distributed_headers = opik_context.get_distributed_trace_headers()

    with distributed_headers_context_manager.distributed_headers(distributed_headers):
        # Create nested spans
        with opik.start_as_current_span("span-1") as span1:
            span1.output = {"level": 1}

            with opik.start_as_current_span("span-2") as span2:
                span2.output = {"level": 2}

    opik.flush_tracker()

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

    trace_tree = fake_backend.trace_trees[0]

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=trace_tree)
