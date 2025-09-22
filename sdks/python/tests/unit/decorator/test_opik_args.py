import asyncio
from typing import Any
from unittest.mock import patch

import pytest

from opik.api_objects.trace import trace_data
from opik.decorator import opik_args, arguments_helpers, tracker
from opik.decorator.tracker import OpikTrackDecorator
from ...testlib import (
    ANY_BUT_NONE,
    SpanModel,
    TraceModel,
    assert_equal,
)


class TestOpikArgsWithTrackDecorator:
    """Test the opik_args parameter functionality with the OpikTrackDecorator."""

    def setup_method(self):
        self.opik_args_dict = {
            "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
            "trace": {
                "thread_id": "conversation-2",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            },
        }

    def test_track__one_nested_function__implicit_opik_args(self, fake_backend):
        # test that the implicit opik_args can be used
        @tracker.track
        def f_inner(x: str):
            return "inner-output"

        @tracker.track
        def f_outer(x: str):
            f_inner("inner-input")
            return "outer-output"

        f_outer("outer-input", opik_args=self.opik_args_dict)
        tracker.flush_tracker()

        expected_trace_tree = self._get_implicit_opik_args_expected_trace_tree()

        assert len(fake_backend.trace_trees) == 1

        assert_equal(expected=expected_trace_tree, actual=fake_backend.trace_trees[0])

    def test_track__one_nested_function__explicit_opik_args(self, fake_backend):
        # tests that explicit opik_args is properly propagated to nested functions
        @tracker.track
        def f_inner(x: str, opik_args: Any = None):
            return "inner-output"

        @tracker.track
        def f_outer(x: str, opik_args: Any = None):
            f_inner("inner-input", opik_args=opik_args)
            return "outer-output"

        f_outer("outer-input", opik_args=self.opik_args_dict)
        tracker.flush_tracker()

        expected_trace_tree = self._get_explicit_opik_args_expected_trace_tree()

        assert len(fake_backend.trace_trees) == 1

        assert_equal(expected=expected_trace_tree, actual=fake_backend.trace_trees[0])

    @pytest.mark.asyncio
    async def test_track__nested_async_function__implicit_opik_args(self, fake_backend):
        # test that the implicit opik_args can be used in async functions
        @tracker.track
        async def f_inner(x):
            await asyncio.sleep(0.01)
            return "inner-output"

        @tracker.track
        async def f_outer(x):
            await f_inner("inner-input")
            return "outer-output"

        await f_outer("outer-input", opik_args=self.opik_args_dict)

        tracker.flush_tracker()

        expected_trace_tree = self._get_implicit_opik_args_expected_trace_tree()

        assert len(fake_backend.trace_trees) == 1

        assert_equal(expected=expected_trace_tree, actual=fake_backend.trace_trees[0])

    @pytest.mark.asyncio
    async def test_track__nested_async_function__explicit_opik_args(self, fake_backend):
        # tests that explicit opik_args is properly propagated to nested functions in async functions
        @tracker.track
        async def f_inner(x: str, opik_args: Any = None):
            await asyncio.sleep(0.01)
            return "inner-output"

        @tracker.track
        async def f_outer(x: str, opik_args: Any = None):
            await f_inner("inner-input", opik_args=opik_args)
            return "outer-output"

        await f_outer("outer-input", opik_args=self.opik_args_dict)

        tracker.flush_tracker()

        expected_trace_tree = self._get_explicit_opik_args_expected_trace_tree()

        assert len(fake_backend.trace_trees) == 1

        assert_equal(expected=expected_trace_tree, actual=fake_backend.trace_trees[0])

    def _get_explicit_opik_args_expected_trace_tree(self):
        return TraceModel(
            id=ANY_BUT_NONE,
            start_time=ANY_BUT_NONE,
            name="f_outer",
            project_name="Default Project",
            input={"x": "outer-input", "opik_args": self.opik_args_dict},
            output={"output": "outer-output"},
            tags=["span_tag", "trace_tag"],
            metadata={"span_key": "span_value", "trace_key": "trace_value"},
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    name="f_outer",
                    input={"x": "outer-input", "opik_args": self.opik_args_dict},
                    output={"output": "outer-output"},
                    tags=["span_tag"],
                    metadata={"span_key": "span_value"},
                    type="general",
                    end_time=ANY_BUT_NONE,
                    project_name="Default Project",
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            start_time=ANY_BUT_NONE,
                            name="f_inner",
                            input={
                                "x": "inner-input",
                                "opik_args": self.opik_args_dict,
                            },
                            output={"output": "inner-output"},
                            tags=["span_tag"],
                            metadata={"span_key": "span_value"},
                            type="general",
                            end_time=ANY_BUT_NONE,
                            project_name="Default Project",
                            last_updated_at=ANY_BUT_NONE,
                        )
                    ],
                    last_updated_at=ANY_BUT_NONE,
                )
            ],
            thread_id="conversation-2",
            last_updated_at=ANY_BUT_NONE,
        )

    @staticmethod
    def _get_implicit_opik_args_expected_trace_tree():
        return TraceModel(
            id=ANY_BUT_NONE,
            start_time=ANY_BUT_NONE,
            name="f_outer",
            project_name="Default Project",
            input={"x": "outer-input"},
            output={"output": "outer-output"},
            tags=["span_tag", "trace_tag"],
            metadata={"span_key": "span_value", "trace_key": "trace_value"},
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    name="f_outer",
                    input={"x": "outer-input"},
                    output={"output": "outer-output"},
                    tags=["span_tag"],
                    metadata={"span_key": "span_value"},
                    type="general",
                    end_time=ANY_BUT_NONE,
                    project_name="Default Project",
                    spans=[
                        SpanModel(
                            id=ANY_BUT_NONE,
                            start_time=ANY_BUT_NONE,
                            name="f_inner",
                            input={"x": "inner-input"},
                            output={"output": "inner-output"},
                            type="general",
                            end_time=ANY_BUT_NONE,
                            project_name="Default Project",
                            last_updated_at=ANY_BUT_NONE,
                        )
                    ],
                    last_updated_at=ANY_BUT_NONE,
                )
            ],
            thread_id="conversation-2",
            last_updated_at=ANY_BUT_NONE,
        )


class TestOpikArgs:
    """Test the opik_args parameter functionality."""

    def setup_method(self):
        """Set up test fixtures."""
        self.decorator = OpikTrackDecorator()

    def test_opik_args_from_dict__none(self):
        """Test OpikArgs.from_dict with None input."""
        result = opik_args.OpikArgs.from_dict(None)
        assert result is None

    def test_opik_args_from_dict__invalid_opik_args_type__warning_logged(self):
        """Test OpikArgs.from_dict with an invalid input type."""
        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            result = opik_args.OpikArgs.from_dict("invalid")
            assert result is None
            mock_logger.warning.assert_called_once()

    def test_opik_args_from_dict__valid_span_only(self):
        """Test OpikArgs.from_dict with a valid span args."""
        args_dict = {"span": {"tags": ["tag1", "tag2"], "metadata": {"key": "value"}}}
        result = opik_args.OpikArgs.from_dict(args_dict)

        assert result is not None
        assert result.span_args is not None
        assert result.span_args.tags == ["tag1", "tag2"]
        assert result.span_args.metadata == {"key": "value"}
        assert result.trace_args is None

    def test_opik_args_from_dict__valid_trace_only(self):
        """Test OpikArgs.from_dict with a valid trace args."""
        args_dict = {
            "trace": {
                "thread_id": "conversation-1",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            }
        }
        result = opik_args.OpikArgs.from_dict(args_dict)

        assert result is not None
        assert result.trace_args is not None
        assert result.trace_args.thread_id == "conversation-1"
        assert result.trace_args.tags == ["trace_tag"]
        assert result.trace_args.metadata == {"trace_key": "trace_value"}
        assert result.span_args is None

    def test_opik_args_from_dict__both_span_and_trace__happy_flow(self):
        """Test OpikArgs.from_dict with both span and trace args."""
        args_dict = {
            "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
            "trace": {
                "thread_id": "conversation-2",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            },
        }
        result = opik_args.OpikArgs.from_dict(args_dict)

        assert result is not None
        assert result.span_args is not None
        assert result.span_args.tags == ["span_tag"]
        assert result.span_args.metadata == {"span_key": "span_value"}
        assert result.trace_args is not None
        assert result.trace_args.thread_id == "conversation-2"
        assert result.trace_args.tags == ["trace_tag"]
        assert result.trace_args.metadata == {"trace_key": "trace_value"}

    def test_opik_args_from_dict_invalid__span_type_type__warning_logged(self):
        """Test OpikArgs.from_dict with an invalid span type."""
        args_dict = {"span": "invalid"}
        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            result = opik_args.OpikArgs.from_dict(args_dict)
            assert result is not None
            assert result.span_args is None
            mock_logger.warning.assert_called_once()

    def test_opik_args_from_dict__invalid_trace_args_type__warning_logged(self):
        """Test OpikArgs.from_dict with an invalid trace type."""
        args_dict = {"trace": "invalid"}
        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            result = opik_args.OpikArgs.from_dict(args_dict)
            assert result is not None
            assert result.trace_args is None
            mock_logger.warning.assert_called_once()

    def test_apply_opik_args_to_start_span_params__no_opik_args(self):
        """Test applying None opik_args to StartSpanParameters."""
        params = arguments_helpers.StartSpanParameters(
            name="test",
            type="general",
            tags=["original_tag"],
            metadata={"original": "value"},
        )
        result = opik_args.apply_opik_args_to_start_span_params(params, None)
        assert result == params

    def test_apply_opik_args_to_start_span_params__no_span_args(self):
        """Test applying opik_args with no span args."""
        params = arguments_helpers.StartSpanParameters(
            name="test",
            type="general",
            tags=["original_tag"],
            metadata={"original": "value"},
        )
        args = opik_args.OpikArgs(span_args=None, trace_args=None)
        result = opik_args.apply_opik_args_to_start_span_params(params, args)
        assert result == params

    def test_apply_opik_args_to_start_span_params__with_span_args(self):
        """Test applying opik_args with span args."""
        params = arguments_helpers.StartSpanParameters(
            name="test",
            type="general",
            tags=["original_tag"],
            metadata={"original": "value"},
        )
        span_args = opik_args.api_classes.OpikArgsSpan(
            tags=["new_tag"], metadata={"new": "value"}
        )
        args = opik_args.OpikArgs(span_args=span_args, trace_args=None)

        result = opik_args.apply_opik_args_to_start_span_params(params, args)

        assert result.name == params.name
        assert result.type == params.type
        assert result.tags == ["original_tag", "new_tag"]
        assert result.metadata == {"original": "value", "new": "value"}

    def test_extract_opik_args__happy_path(self):
        """Test that opik_args is properly extracted from kwargs."""
        kwargs = {
            "arg1": "value1",
            "opik_args": {"span": {"tags": ["test_tag"]}},
            "arg2": "value2",
        }

        # Mock function without opik_args parameter (should pop from kwargs)
        def mock_func(arg1, arg2):
            pass

        args = opik_args.extract_opik_args(kwargs, mock_func)

        assert args is not None
        assert args.span_args is not None
        assert args.span_args.tags == ["test_tag"]
        assert "opik_args" not in kwargs
        assert kwargs == {"arg1": "value1", "arg2": "value2"}

    def test_extract_opik_args__no_data__none_returned(self):
        """Test extracting opik_args when it's not present."""
        from opik.decorator.opik_args import extract_opik_args

        kwargs = {"arg1": "value1", "arg2": "value2"}

        # Mock function without opik_args parameter
        def mock_func(arg1, arg2):
            pass

        args = extract_opik_args(kwargs, mock_func)

        assert args is None
        assert kwargs == {"arg1": "value1", "arg2": "value2"}

    def test_extract_opik_args__function_with_explicit_opik_args(self):
        """Test that opik_args is NOT popped when function has explicit opik_args parameter."""
        kwargs = {
            "arg1": "value1",
            "opik_args": {"span": {"tags": ["test_tag"]}},
            "arg2": "value2",
        }

        # Mock function WITH opik_args parameter (should NOT pop from kwargs)
        def mock_func_with_opik_args(arg1, arg2, opik_args=None):
            pass

        args = opik_args.extract_opik_args(kwargs, mock_func_with_opik_args)

        assert args is not None
        assert args.span_args is not None
        assert args.span_args.tags == ["test_tag"]
        # opik_args should remain in kwargs when function has the parameter
        assert "opik_args" in kwargs
        assert kwargs == {
            "arg1": "value1",
            "opik_args": {"span": {"tags": ["test_tag"]}},
            "arg2": "value2",
        }

    def test_extract_opik_args__function_without_explicit_opik_args(self):
        """Test that opik_args IS popped when the function has no opik_args parameter."""
        kwargs = {
            "arg1": "value1",
            "opik_args": {"span": {"tags": ["test_tag"]}},
            "arg2": "value2",
        }

        # Mock function WITHOUT opik_args parameter (should pop from kwargs)
        def mock_func_without_opik_args(arg1, arg2):
            pass

        args = opik_args.extract_opik_args(kwargs, mock_func_without_opik_args)

        assert args is not None
        assert args.span_args is not None
        assert args.span_args.tags == ["test_tag"]
        # opik_args should be removed from kwargs when the function doesn't have the parameter
        assert "opik_args" not in kwargs
        assert kwargs == {"arg1": "value1", "arg2": "value2"}


class TestApplyOpikArgsToTrace:
    """Test apply_opik_args_to_trace functionality."""

    def test_apply_opik_args_to_trace__no_opik_args(self):
        """Test apply_opik_args_to_trace with None opik_args."""
        # Should not raise any exceptions
        opik_args.apply_opik_args_to_trace(None, None)

    def test_apply_opik_args_to_trace__no_trace_args(self):
        """Test apply_opik_args_to_trace with no trace_args."""
        args = opik_args.OpikArgs(span_args=None, trace_args=None)

        # Should not raise any exceptions
        opik_args.apply_opik_args_to_trace(args, None)

    def test_apply_opik_args_to_trace__no_thread_id(self):
        """Test apply_opik_args_to_trace with no thread_id."""
        trace_args = opik_args.api_classes.OpikArgsTrace(
            thread_id=None, tags=["test_tag"]
        )
        args = opik_args.OpikArgs(span_args=None, trace_args=trace_args)

        # Should not raise any exceptions
        opik_args.apply_opik_args_to_trace(args, None)

    def test_apply_opik_args_to_trace__no_trace(self):
        """Test apply_opik_args_to_trace when no trace_data is provided."""
        trace_args = opik_args.api_classes.OpikArgsTrace(thread_id="test-thread")
        args = opik_args.OpikArgs(span_args=None, trace_args=trace_args)

        # Should not raise any exceptions when current_trace_data is None
        opik_args.apply_opik_args_to_trace(args, None)

    def test_apply_opik_args_to_trace__thread_id_exists__no_changes__warning_logged(
        self,
    ):
        """Test apply_opik_args_to_trace with thread_id conflict."""
        trace_args = opik_args.api_classes.OpikArgsTrace(thread_id="new-thread")
        args = opik_args.OpikArgs(span_args=None, trace_args=trace_args)

        # existing trace data with different thread_id
        trace = trace_data.TraceData()
        trace.thread_id = "existing-thread"

        with patch("opik.decorator.opik_args.helpers.LOGGER") as mock_logger:
            opik_args.apply_opik_args_to_trace(args, trace)
            mock_logger.warning.assert_called_once()
            # Thread ID should not be changed
            assert trace.thread_id == "existing-thread"

    def test_apply_opik_args_to_trace__happy_flow(self):
        """Test apply_opik_args_to_trace successful application."""
        trace_args = opik_args.api_classes.OpikArgsTrace(
            thread_id="new-thread",
            tags=["trace_tag"],
            metadata={"trace_key": "trace_value"},
        )
        args = opik_args.OpikArgs(span_args=None, trace_args=trace_args)

        # existing trace data with no thread_id
        trace = trace_data.TraceData()
        trace.thread_id = None
        trace.tags = ["existing_tag"]
        trace.metadata = {"existing": "value"}

        opik_args.apply_opik_args_to_trace(args, trace)

        # Verify thread_id was set
        assert trace.thread_id == "new-thread"
        # Verify tags were merged
        assert trace.tags == ["existing_tag", "trace_tag"]
        # Verify metadata was merged
        assert trace.metadata == {
            "existing": "value",
            "trace_key": "trace_value",
        }

    def test_apply_opik_args_to_trace__same_thread_id__warning_logged(self):
        """Test apply_opik_args_to_trace with the same thread_id (no conflict)."""
        trace_args = opik_args.api_classes.OpikArgsTrace(thread_id="same-thread")
        args = opik_args.OpikArgs(span_args=None, trace_args=trace_args)

        # existing trace data with the same thread_id
        trace = trace_data.TraceData()
        trace.thread_id = "same-thread"

        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            opik_args.apply_opik_args_to_trace(args, trace)
            # No warning should be logged for the same thread_id
            mock_logger.warning.assert_not_called()
            # Thread ID should remain the same
            assert trace.thread_id == "same-thread"
