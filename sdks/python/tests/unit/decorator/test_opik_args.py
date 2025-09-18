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

    def test_track__one_nested_function__implicit_opik_args(self, fake_backend):
        # test that the implicit opik_args can be used
        @tracker.track
        def f_inner(x: str):
            return "inner-output"

        @tracker.track
        def f_outer(x: str):
            f_inner("inner-input")
            return "outer-output"

        opik_args_dict = {
            "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
            "trace": {
                "thread_id": "conversation-2",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            },
        }

        f_outer("outer-input", opik_args=opik_args_dict)
        tracker.flush_tracker()

        expected_trace_tree = TraceModel(
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

        opik_args_dict = {
            "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
            "trace": {
                "thread_id": "conversation-2",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            },
        }

        f_outer("outer-input", opik_args=opik_args_dict)
        tracker.flush_tracker()

        expected_trace_tree = TraceModel(
            id=ANY_BUT_NONE,
            start_time=ANY_BUT_NONE,
            name="f_outer",
            project_name="Default Project",
            input={"x": "outer-input", "opik_args": opik_args_dict},
            output={"output": "outer-output"},
            tags=["span_tag", "trace_tag"],
            metadata={"span_key": "span_value", "trace_key": "trace_value"},
            end_time=ANY_BUT_NONE,
            spans=[
                SpanModel(
                    id=ANY_BUT_NONE,
                    start_time=ANY_BUT_NONE,
                    name="f_outer",
                    input={"x": "outer-input", "opik_args": opik_args_dict},
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
                            input={"x": "inner-input", "opik_args": opik_args_dict},
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

        assert len(fake_backend.trace_trees) == 1

        assert_equal(expected=expected_trace_tree, actual=fake_backend.trace_trees[0])


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
        """Test OpikConfig.from_dict with an invalid input type."""
        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            result = opik_args.OpikArgs.from_dict("invalid")
            assert result is None
            mock_logger.warning.assert_called_once()

    def test_opik_args_from_dict__valid_span_only(self):
        """Test OpikConfig.from_dict with a valid span configuration."""
        config_dict = {"span": {"tags": ["tag1", "tag2"], "metadata": {"key": "value"}}}
        result = opik_args.OpikArgs.from_dict(config_dict)

        assert result is not None
        assert result.span_args is not None
        assert result.span_args.tags == ["tag1", "tag2"]
        assert result.span_args.metadata == {"key": "value"}
        assert result.trace_args is None

    def test_opik_args_from_dict__valid_trace_only(self):
        """Test OpikConfig.from_dict with a valid trace configuration."""
        config_dict = {
            "trace": {
                "thread_id": "conversation-1",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            }
        }
        result = opik_args.OpikArgs.from_dict(config_dict)

        assert result is not None
        assert result.trace_args is not None
        assert result.trace_args.thread_id == "conversation-1"
        assert result.trace_args.tags == ["trace_tag"]
        assert result.trace_args.metadata == {"trace_key": "trace_value"}
        assert result.span_args is None

    def test_opik_args_from_dict__both_span_and_trace__happy_flow(self):
        """Test OpikConfig.from_dict with both span and trace configuration."""
        config_dict = {
            "span": {"tags": ["span_tag"], "metadata": {"span_key": "span_value"}},
            "trace": {
                "thread_id": "conversation-2",
                "tags": ["trace_tag"],
                "metadata": {"trace_key": "trace_value"},
            },
        }
        result = opik_args.OpikArgs.from_dict(config_dict)

        assert result is not None
        assert result.span_args is not None
        assert result.span_args.tags == ["span_tag"]
        assert result.span_args.metadata == {"span_key": "span_value"}
        assert result.trace_args is not None
        assert result.trace_args.thread_id == "conversation-2"
        assert result.trace_args.tags == ["trace_tag"]
        assert result.trace_args.metadata == {"trace_key": "trace_value"}

    def test_opik_args_from_dict_invalid__span_type_type__warning_logged(self):
        """Test OpikConfig.from_dict with invalid span type."""
        config_dict = {"span": "invalid"}
        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            result = opik_args.OpikArgs.from_dict(config_dict)
            assert result is not None
            assert result.span_args is None
            mock_logger.warning.assert_called_once()

    def test_opik_args_from_dict__invalid_trace_args_type__warning_logged(self):
        """Test OpikConfig.from_dict with invalid trace type."""
        config_dict = {"trace": "invalid"}
        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            result = opik_args.OpikArgs.from_dict(config_dict)
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
        """Test applying opik_args with no span configuration."""
        params = arguments_helpers.StartSpanParameters(
            name="test",
            type="general",
            tags=["original_tag"],
            metadata={"original": "value"},
        )
        config = opik_args.OpikArgs(span_args=None, trace_args=None)
        result = opik_args.apply_opik_args_to_start_span_params(params, config)
        assert result == params

    def test_apply_opik_args_to_start_span_params__with_span_args(self):
        """Test applying opik_args with span configuration."""
        params = arguments_helpers.StartSpanParameters(
            name="test",
            type="general",
            tags=["original_tag"],
            metadata={"original": "value"},
        )
        span_config = opik_args.api_classes.OpikArgsSpan(
            tags=["new_tag"], metadata={"new": "value"}
        )
        config = opik_args.OpikArgs(span_args=span_config, trace_args=None)

        result = opik_args.apply_opik_args_to_start_span_params(params, config)

        assert result.name == params.name
        assert result.type == params.type
        assert result.tags == ["original_tag", "new_tag"]
        assert result.metadata == {"original": "value", "new": "value"}

    def test_tracked_function_with_opik_args_span(self):
        """Test tracked function with opik_args span parameters."""

        @self.decorator.track()
        def test_function(x, y, opik_args=None):
            return x + y

        with patch(
            "opik.decorator.tracing_runtime_config.is_tracing_active", return_value=True
        ):
            with patch.object(self.decorator, "_before_call") as mock_before:
                with patch.object(self.decorator, "_after_call") as mock_after:
                    result = test_function(
                        1,
                        2,
                        opik_args={
                            "span": {
                                "tags": ["custom_tag"],
                                "metadata": {"custom_key": "custom_value"},
                            }
                        },
                    )

                    assert result == 3
                    mock_before.assert_called_once()
                    mock_after.assert_called_once()

    @pytest.mark.asyncio
    async def test_tracked_async_function_with_opik_args(self):
        """Test tracked async function with opik_args parameters."""

        @self.decorator.track()
        async def async_test_function(x, y, opik_args=None):
            return x + y

        with patch(
            "opik.decorator.tracing_runtime_config.is_tracing_active", return_value=True
        ):
            with patch.object(self.decorator, "_before_call") as mock_before:
                with patch.object(self.decorator, "_after_call") as mock_after:
                    result = await async_test_function(
                        1, 2, opik_args={"span": {"tags": ["async_tag"]}}
                    )

                    assert result == 3
                    mock_before.assert_called_once()
                    mock_after.assert_called_once()

    def test_tracked_function_without_opik_args(self):
        """Test tracked function without opik_args parameter."""

        @self.decorator.track()
        def test_function(x, y):
            return x + y

        with patch(
            "opik.decorator.tracing_runtime_config.is_tracing_active", return_value=True
        ):
            with patch.object(self.decorator, "_before_call") as mock_before:
                with patch.object(self.decorator, "_after_call") as mock_after:
                    result = test_function(1, 2)

                    assert result == 3
                    mock_before.assert_called_once()
                    mock_after.assert_called_once()

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

        config, cleaned_kwargs = opik_args.extract_opik_args(kwargs, mock_func)

        assert config is not None
        assert config.span_args is not None
        assert config.span_args.tags == ["test_tag"]
        assert "opik_args" not in cleaned_kwargs
        assert cleaned_kwargs == {"arg1": "value1", "arg2": "value2"}

    def test_extract_opik_args__no_data__none_returned(self):
        """Test extracting opik_args when it's not present."""
        from opik.decorator.opik_args import extract_opik_args

        kwargs = {"arg1": "value1", "arg2": "value2"}

        # Mock function without opik_args parameter
        def mock_func(arg1, arg2):
            pass

        config, cleaned_kwargs = extract_opik_args(kwargs, mock_func)

        assert config is None
        assert cleaned_kwargs == {"arg1": "value1", "arg2": "value2"}

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

        config, cleaned_kwargs = opik_args.extract_opik_args(
            kwargs, mock_func_with_opik_args
        )

        assert config is not None
        assert config.span_args is not None
        assert config.span_args.tags == ["test_tag"]
        # opik_args should remain in kwargs when function has the parameter
        assert "opik_args" in cleaned_kwargs
        assert cleaned_kwargs == {
            "arg1": "value1",
            "opik_args": {"span": {"tags": ["test_tag"]}},
            "arg2": "value2",
        }

    def test_extract_opik_args__function_without_explicit_opik_args(self):
        """Test that opik_args IS popped when function has no opik_args parameter."""
        kwargs = {
            "arg1": "value1",
            "opik_args": {"span": {"tags": ["test_tag"]}},
            "arg2": "value2",
        }

        # Mock function WITHOUT opik_args parameter (should pop from kwargs)
        def mock_func_without_opik_args(arg1, arg2):
            pass

        config, cleaned_kwargs = opik_args.extract_opik_args(
            kwargs, mock_func_without_opik_args
        )

        assert config is not None
        assert config.span_args is not None
        assert config.span_args.tags == ["test_tag"]
        # opik_args should be removed from kwargs when function doesn't have the parameter
        assert "opik_args" not in cleaned_kwargs
        assert cleaned_kwargs == {"arg1": "value1", "arg2": "value2"}


class TestApplyOpikArgsToTrace:
    """Test apply_opik_args_to_trace functionality."""

    def test_apply_opik_args_to_trace__no_opik_args(self):
        """Test apply_opik_args_to_trace with None opik_args."""
        # Should not raise any exceptions
        opik_args.apply_opik_args_to_trace(None, None)

    def test_apply_opik_args_to_trace__no_trace_args(self):
        """Test apply_opik_args_to_trace with no trace_args."""
        config = opik_args.OpikArgs(span_args=None, trace_args=None)

        # Should not raise any exceptions
        opik_args.apply_opik_args_to_trace(config, None)

    def test_apply_opik_args_to_trace__no_thread_id(self):
        """Test apply_opik_args_to_trace with no thread_id."""
        trace_config = opik_args.api_classes.OpikArgsTrace(
            thread_id=None, tags=["test_tag"]
        )
        config = opik_args.OpikArgs(span_args=None, trace_args=trace_config)

        # Should not raise any exceptions
        opik_args.apply_opik_args_to_trace(config, None)

    def test_apply_opik_args_to_trace__no_trace(self):
        """Test apply_opik_args_to_trace when no trace_data is provided."""
        trace_config = opik_args.api_classes.OpikArgsTrace(thread_id="test-thread")
        config = opik_args.OpikArgs(span_args=None, trace_args=trace_config)

        # Should not raise any exceptions when current_trace_data is None
        opik_args.apply_opik_args_to_trace(config, None)

    def test_apply_opik_args_to_trace__thread_id_exists__no_changes__warning_logged(
        self,
    ):
        """Test apply_opik_args_to_trace with thread_id conflict."""
        trace_config = opik_args.api_classes.OpikArgsTrace(thread_id="new-thread")
        config = opik_args.OpikArgs(span_args=None, trace_args=trace_config)

        # existing trace data with different thread_id
        trace = trace_data.TraceData()
        trace.thread_id = "existing-thread"

        with patch("opik.decorator.opik_args.helpers.LOGGER") as mock_logger:
            opik_args.apply_opik_args_to_trace(config, trace)
            mock_logger.warning.assert_called_once()
            # Thread ID should not be changed
            assert trace.thread_id == "existing-thread"

    def test_apply_opik_args_to_trace__happy_flow(self):
        """Test apply_opik_args_to_trace successful application."""
        trace_config = opik_args.api_classes.OpikArgsTrace(
            thread_id="new-thread",
            tags=["trace_tag"],
            metadata={"trace_key": "trace_value"},
        )
        config = opik_args.OpikArgs(span_args=None, trace_args=trace_config)

        # existing trace data with no thread_id
        trace = trace_data.TraceData()
        trace.thread_id = None
        trace.tags = ["existing_tag"]
        trace.metadata = {"existing": "value"}

        opik_args.apply_opik_args_to_trace(config, trace)

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
        trace_config = opik_args.api_classes.OpikArgsTrace(thread_id="same-thread")
        config = opik_args.OpikArgs(span_args=None, trace_args=trace_config)

        # existing trace data with the same thread_id
        trace = trace_data.TraceData()
        trace.thread_id = "same-thread"

        with patch("opik.decorator.opik_args.api_classes.LOGGER") as mock_logger:
            opik_args.apply_opik_args_to_trace(config, trace)
            # No warning should be logged for the same thread_id
            mock_logger.warning.assert_not_called()
            # Thread ID should remain the same
            assert trace.thread_id == "same-thread"


class TestOpikArgsHelpers:
    def test_merge_tags_both_none(self):
        """Test merge_tags with both inputs None."""
        result = opik_args.helpers.merge_tags(None, None)
        assert result is None

    def test_merge_tags_existing_none(self):
        """Test merge_tags with existing tags None."""
        result = opik_args.helpers.merge_tags(None, ["new_tag"])
        assert result == ["new_tag"]

    def test_merge_tags_new_none(self):
        """Test merge_tags with new tags None."""
        result = opik_args.helpers.merge_tags(["existing_tag"], None)
        assert result == ["existing_tag"]

    def test_merge_tags_no_duplicates(self):
        """Test merge_tags with no duplicates."""
        result = opik_args.helpers.merge_tags(["tag1"], ["tag2", "tag3"])
        assert result == ["tag1", "tag2", "tag3"]

    def test_merge_tags_with_duplicates(self):
        """Test merge_tags with duplicates."""
        result = opik_args.helpers.merge_tags(["tag1", "tag2"], ["tag2", "tag3"])
        assert result == ["tag1", "tag2", "tag3"]

    def test_merge_tags_empty_lists(self):
        """Test merge_tags with empty lists."""
        result = opik_args.helpers.merge_tags([], [])
        assert result is None

    def test_merge_metadata_both_none(self):
        """Test merge_metadata with both inputs None."""
        result = opik_args.helpers.merge_metadata(None, None)
        assert result is None

    def test_merge_metadata_existing_none(self):
        """Test merge_metadata with existing metadata None."""
        result = opik_args.helpers.merge_metadata(None, {"key": "value"})
        assert result == {"key": "value"}

    def test_merge_metadata_new_none(self):
        """Test merge_metadata with new metadata None."""
        result = opik_args.helpers.merge_metadata({"key": "value"}, None)
        assert result == {"key": "value"}

    def test_merge_metadata_no_conflicts(self):
        """Test merge_metadata with no key conflicts."""
        result = opik_args.helpers.merge_metadata(
            {"key1": "value1"}, {"key2": "value2"}
        )
        assert result == {"key1": "value1", "key2": "value2"}

    def test_merge_metadata_with_conflicts(self):
        """Test merge_metadata with key conflicts (new values win)."""
        result = opik_args.helpers.merge_metadata(
            {"key": "old_value", "other": "kept"}, {"key": "new_value"}
        )
        assert result == {"key": "new_value", "other": "kept"}

    def test_merge_metadata_empty_dicts(self):
        """Test merge_metadata with empty dictionaries."""
        result = opik_args.helpers.merge_metadata({}, {})
        assert result is None
