import pytest

import opik
from opik.api_objects import attachment
from opik.types import FeedbackScoreDict

from ...testlib import (
    ANY_BUT_NONE,
    FeedbackScoreModel,
    SpanModel,
    TraceModel,
    assert_equal,
)


def test_start_as_current_span__inside__happy_flow(fake_backend):
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
        span.attachments = [attachment.Attachment(data="./test_file.txt")]

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
            flush=True,
        ):
            raise Exception("Test exception")

    assert exc_info.value.args[0] == "Test exception"


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
