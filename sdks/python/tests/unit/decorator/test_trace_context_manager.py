import pytest

import opik
from ...testlib import (
    ANY_BUT_NONE,
    TraceModel,
    assert_equal,
)


def test_start_as_current_trace__inside__happy_flow(fake_backend):
    """Test that the trace parameters are logged correctly when the trace is updated inside the context manager."""
    with opik.start_as_current_trace(
        "test-trace", project_name="test-project", flush=True
    ) as trace:
        trace.tags = ["one", "two", "three"]
        trace.metadata = {"one": "first", "two": "second", "three": "third"}
        trace.input = {"input": "test-input"}
        trace.output = {"output": "test-output"}
        trace.thread_id = "test-thread-123"

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-trace",
        project_name="test-project",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "three": "third", "two": "second"},
        end_time=ANY_BUT_NONE,
        spans=[],
        last_updated_at=ANY_BUT_NONE,
        thread_id="test-thread-123",
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_trace__outside__happy_flow(fake_backend):
    """Test that the trace parameters are logged correctly when the trace is updated outside the context manager."""
    with opik.start_as_current_trace(
        "test-trace",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "two": "second", "three": "third"},
        project_name="test-project",
        thread_id="test-thread-123",
        flush=True,
    ):
        pass  # No modifications inside context manager

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-trace",
        project_name="test-project",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "three": "third", "two": "second"},
        thread_id="test-thread-123",
        end_time=ANY_BUT_NONE,
        spans=[],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_trace__mixed__inside_override_outside(fake_backend):
    """Test that the trace tags, metadata, input, output are overridden by the trace.tags and trace.metadata inside the context manager."""
    with opik.start_as_current_trace(
        "test-trace",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "two": "second", "three": "third"},
        project_name="test-project",
        thread_id="test-thread-123",
        flush=True,
    ) as trace:
        trace.tags = ["four"]
        trace.metadata = {"four": "fourth"}
        trace.input = {"input": "new-test-input"}
        trace.output = {"output": "new-test-output"}
        trace.thread_id = "new-thread-123"

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-trace",
        project_name="test-project",
        input={"input": "new-test-input"},
        output={"output": "new-test-output"},
        tags=["four"],
        metadata={
            "four": "fourth",
        },
        thread_id="new-thread-123",
        end_time=ANY_BUT_NONE,
        spans=[],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_trace__user_error__logged_and_raised(fake_backend):
    """Test that the user error is logged and raised properly."""
    with pytest.raises(Exception) as exc_info:
        with opik.start_as_current_trace(
            "test-trace",
            input={"input": "test-input"},
            output={"output": "test-output"},
            tags=["one", "two", "three"],
            metadata={"one": "first", "two": "second", "three": "third"},
            project_name="test-project",
            flush=True,
        ):
            raise Exception("Test exception")

    assert exc_info.value.args[0] == "Test exception"


def test_start_as_current_trace__minimal_parameters__works(fake_backend):
    """Test that the trace context manager works with minimal parameters."""
    with opik.start_as_current_trace("minimal-trace", flush=True):
        pass  # No modifications

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="minimal-trace",
        project_name="Default Project",
        end_time=ANY_BUT_NONE,
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])
