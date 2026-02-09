import pytest

import opik
import opik.opik_context
from opik.api_objects import attachment
from opik.types import FeedbackScoreDict, ErrorInfoDict

from tests.testlib import (
    ANY_BUT_NONE,
    ANY_STRING,
    FeedbackScoreModel,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_start_as_current_span__inside__happy_flow(fake_backend, temp_file_15kb):
    """Test that the span parameters are logged correctly when the span is updated inside the context manager."""
    with opik.start_as_current_span(
        "test-span", project_name="test-project", flush=True
    ) as span:
        span.tags = ["one", "two", "three"]
        span.metadata = {"one": "first", "two": "second", "three": "third"}
        span.model = "gpt-3.5-turbo"
        span.provider = "openai"
        span.usage = {
            "completion_tokens": 101,
            "prompt_tokens": 20,
            "total_tokens": 121,
        }

        span.feedback_scores = [FeedbackScoreDict(name="feedback-score-1", value=0.5)]
        span.input = {"input": "test-input"}
        span.output = {"output": "test-output"}
        span.attachments = [
            attachment.Attachment(data=temp_file_15kb.name, create_temp_copy=False)
        ]

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-span",
        project_name="test-project",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "three": "third", "two": "second"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="test-span",
                input={"input": "test-input"},
                output={"output": "test-output"},
                tags=["one", "two", "three"],
                metadata={
                    "one": "first",
                    "three": "third",
                    "two": "second",
                    "usage": {
                        "completion_tokens": 101,
                        "prompt_tokens": 20,
                        "total_tokens": 121,
                    },
                },
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="test-project",
                spans=[],
                feedback_scores=[
                    FeedbackScoreModel(
                        id=ANY_BUT_NONE,
                        name="feedback-score-1",
                        value=0.5,
                    )
                ],
                model="gpt-3.5-turbo",
                provider="openai",
                usage={
                    "completion_tokens": 101,
                    "prompt_tokens": 20,
                    "total_tokens": 121,
                    "original_usage.completion_tokens": 101,
                    "original_usage.prompt_tokens": 20,
                    "original_usage.total_tokens": 121,
                },
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_span__outside__happy_flow(fake_backend):
    """Test that the span parameters are logged correctly when the span is updated outside the context manager."""
    with opik.start_as_current_span(
        "test-span",
        type="llm",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "two": "second", "three": "third"},
        project_name="test-project",
        model="gpt-3.5-turbo",
        provider="openai",
        flush=True,
    ) as span:
        span.usage = {
            "completion_tokens": 101,
            "prompt_tokens": 20,
            "total_tokens": 121,
        }

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-span",
        project_name="test-project",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "three": "third", "two": "second"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="test-span",
                input={"input": "test-input"},
                output={"output": "test-output"},
                tags=["one", "two", "three"],
                metadata={
                    "one": "first",
                    "three": "third",
                    "two": "second",
                    "usage": {
                        "completion_tokens": 101,
                        "prompt_tokens": 20,
                        "total_tokens": 121,
                    },
                },
                type="llm",
                usage={
                    "completion_tokens": 101,
                    "original_usage.completion_tokens": 101,
                    "original_usage.prompt_tokens": 20,
                    "original_usage.total_tokens": 121,
                    "prompt_tokens": 20,
                    "total_tokens": 121,
                },
                end_time=ANY_BUT_NONE,
                project_name="test-project",
                model="gpt-3.5-turbo",
                provider="openai",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_span__mixed__inside_override_outside(fake_backend):
    """Test that the span tags, metadata, input, output are overridden by the span.tags and span.metadata inside the context manager."""
    with opik.start_as_current_span(
        "test-span",
        type="llm",
        input={"input": "test-input"},
        output={"output": "test-output"},
        tags=["one", "two", "three"],
        metadata={"one": "first", "two": "second", "three": "third"},
        project_name="test-project",
        model="gpt-3.5-turbo",
        provider="openai",
        flush=True,
    ) as span:
        span.tags = ["four"]
        span.metadata = {"four": "fourth"}
        span.input = {"input": "new-test-input"}
        span.output = {"output": "new-test-output"}
        span.usage = {
            "completion_tokens": 101,
            "prompt_tokens": 20,
            "total_tokens": 121,
        }

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-span",
        project_name="test-project",
        input={"input": "new-test-input"},
        output={"output": "new-test-output"},
        tags=["four"],
        metadata={
            "four": "fourth",
        },
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="test-span",
                input={"input": "new-test-input"},
                output={"output": "new-test-output"},
                tags=["four"],
                metadata={
                    "four": "fourth",
                    "usage": {
                        "completion_tokens": 101,
                        "prompt_tokens": 20,
                        "total_tokens": 121,
                    },
                },
                type="llm",
                usage={
                    "completion_tokens": 101,
                    "original_usage.completion_tokens": 101,
                    "original_usage.prompt_tokens": 20,
                    "original_usage.total_tokens": 121,
                    "prompt_tokens": 20,
                    "total_tokens": 121,
                },
                end_time=ANY_BUT_NONE,
                project_name="test-project",
                model="gpt-3.5-turbo",
                provider="openai",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_span__user_error__logged_and_raised(fake_backend):
    """Test that the user error is added to the span."""
    with pytest.raises(Exception) as exc_info:
        with opik.start_as_current_span(
            "test-span",
            type="llm",
            input={"input": "test-input"},
            output={"output": "test-output"},
            tags=["one", "two", "three"],
            metadata={"one": "first", "two": "second", "three": "third"},
            project_name="test-project",
            model="gpt-3.5-turbo",
            provider="openai",
            flush=True,
        ):
            raise Exception("Test exception")

    assert exc_info.value.args[0] == "Test exception"

    assert len(fake_backend.trace_trees) == 1
    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-span",
        project_name="test-project",
        input={"input": "test-input"},
        output=None,
        tags=["one", "two", "three"],
        metadata={"one": "first", "two": "second", "three": "third"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="test-span",
                input={"input": "test-input"},
                output=None,
                tags=["one", "two", "three"],
                metadata={"one": "first", "two": "second", "three": "third"},
                type="llm",
                end_time=ANY_BUT_NONE,
                project_name="test-project",
                model="gpt-3.5-turbo",
                provider="openai",
                error_info=ErrorInfoDict(
                    exception_type="Exception",
                    message="Test exception",
                    traceback=ANY_STRING,
                ),
                total_cost=None,
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        error_info=ErrorInfoDict(
            exception_type="Exception",
            message="Test exception",
            traceback=ANY_STRING,
        ),
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_span__distributed_headers_provided__child_span_created(
    fake_backend,
):
    """Test that distributed headers are used to create the span."""
    # create parent span
    with opik.start_as_current_span(
        "test-parent-span", project_name="test-project", flush=True
    ) as parent_span:
        parent_span.output = {"output": "parent-test-output"}

    # create child span via distributed headers
    with opik.start_as_current_span(
        "test-child-span",
        type="llm",
        output={"output": "test-output"},
        project_name="test-project",
        flush=True,
        opik_distributed_trace_headers={
            "opik_parent_span_id": parent_span.id,
            "opik_trace_id": parent_span.trace_id,
        },
    ) as span:
        span.usage = {
            "completion_tokens": 101,
            "prompt_tokens": 20,
            "total_tokens": 121,
        }

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="test-parent-span",
        project_name="test-project",
        output={"output": "parent-test-output"},
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="test-parent-span",
                output={"output": "parent-test-output"},
                type="general",
                end_time=ANY_BUT_NONE,
                project_name="test-project",
                spans=[
                    SpanModel(
                        id=ANY_BUT_NONE,
                        start_time=ANY_BUT_NONE,
                        name="test-child-span",
                        output={"output": "test-output"},
                        type="llm",
                        usage={
                            "completion_tokens": 101,
                            "original_usage.completion_tokens": 101,
                            "original_usage.prompt_tokens": 20,
                            "original_usage.total_tokens": 121,
                            "prompt_tokens": 20,
                            "total_tokens": 121,
                        },
                        end_time=ANY_BUT_NONE,
                        project_name="test-project",
                        last_updated_at=ANY_BUT_NONE,
                    )
                ],
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )
    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_span__parent_trace_exists__new_not_created(fake_backend):
    """Test that the parent trace is used if it exists."""
    with opik.start_as_current_trace(
        "minimal-trace", project_name="test-project", flush=True
    ):
        with opik.start_as_current_span(
            "test-span",
            type="llm",
            input={"input": "test-input"},
            output={"output": "test-output"},
            tags=["one", "two", "three"],
            metadata={"one": "first", "two": "second", "three": "third"},
            model="gpt-3.5-turbo",
            provider="openai",
        ) as span:
            span.usage = {
                "completion_tokens": 101,
                "prompt_tokens": 20,
                "total_tokens": 121,
            }

    assert len(fake_backend.trace_trees) == 1

    EXPECTED_TRACE_TREE = TraceModel(
        id=ANY_BUT_NONE,
        start_time=ANY_BUT_NONE,
        name="minimal-trace",
        project_name="test-project",
        end_time=ANY_BUT_NONE,
        spans=[
            SpanModel(
                id=ANY_BUT_NONE,
                start_time=ANY_BUT_NONE,
                name="test-span",
                input={"input": "test-input"},
                output={"output": "test-output"},
                tags=["one", "two", "three"],
                metadata={
                    "one": "first",
                    "three": "third",
                    "two": "second",
                    "usage": {
                        "completion_tokens": 101,
                        "prompt_tokens": 20,
                        "total_tokens": 121,
                    },
                },
                type="llm",
                usage={
                    "completion_tokens": 101,
                    "original_usage.completion_tokens": 101,
                    "original_usage.prompt_tokens": 20,
                    "original_usage.total_tokens": 121,
                    "prompt_tokens": 20,
                    "total_tokens": 121,
                },
                end_time=ANY_BUT_NONE,
                project_name="test-project",
                model="gpt-3.5-turbo",
                provider="openai",
                last_updated_at=ANY_BUT_NONE,
            )
        ],
        last_updated_at=ANY_BUT_NONE,
    )

    assert_equal(expected=EXPECTED_TRACE_TREE, actual=fake_backend.trace_trees[0])


def test_start_as_current_span__context_cleanup__span_and_trace_removed_after_exit(
    fake_backend,
):
    """Test that the span and trace are properly removed from context after the context manager exits."""
    # Verify no trace or span exists before
    assert opik.opik_context.get_current_trace_data() is None
    assert opik.opik_context.get_current_span_data() is None

    # Create span (which will also create a trace) and verify they exist inside context
    with opik.start_as_current_span("test-span", flush=True) as span:
        current_span = opik.opik_context.get_current_span_data()
        current_trace = opik.opik_context.get_current_trace_data()
        assert current_span is not None
        assert current_span.id == span.id
        assert current_trace is not None
        assert current_trace.id == span.trace_id

    # Verify span and trace are removed from context after exit
    assert opik.opik_context.get_current_trace_data() is None
    assert opik.opik_context.get_current_span_data() is None

    # Verify span and trace were logged to backend
    assert len(fake_backend.trace_trees) == 1
    assert fake_backend.trace_trees[0].name == "test-span"
    assert len(fake_backend.trace_trees[0].spans) == 1
    assert fake_backend.trace_trees[0].spans[0].name == "test-span"


def test_start_as_current_span__context_cleanup__with_parent_trace__only_span_removed(
    fake_backend,
):
    """Test that when a parent trace exists, only the span is removed from context after exit."""
    # Create parent trace
    with opik.start_as_current_trace("parent-trace", flush=True) as parent_trace:
        # Verify no span exists before
        assert opik.opik_context.get_current_span_data() is None

        # Create span and verify it exists inside context
        with opik.start_as_current_span("test-span", flush=True) as span:
            current_span = opik.opik_context.get_current_span_data()
            current_trace = opik.opik_context.get_current_trace_data()
            assert current_span is not None
            assert current_span.id == span.id
            assert current_trace is not None
            assert current_trace.id == parent_trace.id

        # Verify span is removed but parent trace remains
        assert opik.opik_context.get_current_span_data() is None
        assert opik.opik_context.get_current_trace_data() is not None
        assert opik.opik_context.get_current_trace_data().id == parent_trace.id

    # Verify parent trace is removed after parent context exits
    assert opik.opik_context.get_current_trace_data() is None
