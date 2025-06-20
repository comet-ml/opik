import pytest
from unittest.mock import patch
from opik.decorator import set_tracing_active, is_tracing_active
from opik import track


@pytest.fixture(autouse=True)
def reset_tracing_state():
    """Reset tracing state before and after each test."""
    # We need to import the module to access the module-level variable
    from opik.decorator import base_track_decorator

    original_state = base_track_decorator._is_runtime_tracing_enabled
    # Start each test with a clean slate, so it falls back to config by default
    base_track_decorator._is_runtime_tracing_enabled = None
    yield
    # Restore original state after test
    base_track_decorator._is_runtime_tracing_enabled = original_state


class TestDynamicTracing:
    def test_default_tracing_enabled(self):
        """Test that tracing is enabled by default (when config is not set to disable)."""
        assert is_tracing_active() is True

    def test_set_tracing_active(self):
        """Test setting tracing active/inactive."""
        set_tracing_active(False)
        assert is_tracing_active() is False

        set_tracing_active(True)
        assert is_tracing_active() is True

    def test_function_remains_decorated_when_tracing_disabled(self):
        """Test that functions stay decorated even when tracing is disabled."""
        call_count = 0

        @track
        def test_function():
            nonlocal call_count
            call_count += 1
            return "test_result"

        # Disable tracing
        set_tracing_active(False)

        # Function should still work
        result = test_function()
        assert result == "test_result"
        assert call_count == 1

        # Function should still have the opik_tracked attribute
        assert hasattr(test_function, "opik_tracked")
        assert test_function.opik_tracked is True

    def test_traces_only_submitted_when_enabled(self, fake_backend):
        """Test that traces are only submitted when tracing is enabled."""

        @track(flush=True)
        def test_function():
            return "test_result"

        # With tracing disabled
        set_tracing_active(False)
        test_function()

        # Should not have submitted any traces
        assert len(fake_backend.trace_trees) == 0

        # With tracing enabled
        set_tracing_active(True)
        test_function()

        # Should have submitted traces
        assert len(fake_backend.trace_trees) == 1

    def test_runtime_changes_work(self, fake_backend):
        """Test that runtime changes work correctly."""

        @track(flush=True)
        def test_function():
            return "test_result"

        # Start with tracing disabled
        set_tracing_active(False)
        test_function()
        assert len(fake_backend.trace_trees) == 0

        # Enable tracing
        set_tracing_active(True)
        test_function()

        # Should have more traces now
        assert len(fake_backend.trace_trees) == 1

    def test_configuration_priority(self):
        """Test that runtime setting takes priority over config."""

        # 1. Config says DISABLE, but runtime is set to TRUE. Result: ENABLED
        with patch.dict("os.environ", {"OPIK_TRACK_DISABLE": "true"}):
            set_tracing_active(True)
            assert is_tracing_active() is True

        # 2. Config says ENABLE (default), but runtime is set to FALSE. Result: DISABLED
        set_tracing_active(False)
        assert is_tracing_active() is False

    def test_environment_variable_respected_when_runtime_not_set(self):
        """Test that environment variables are respected when runtime toggle is NOT set."""
        with patch.dict("os.environ", {"OPIK_TRACK_DISABLE": "true"}):
            # Runtime toggle is not set because of the fixture
            assert is_tracing_active() is False

    def test_async_functions_work_with_dynamic_tracing(self, fake_backend):
        """Test that async functions work with dynamic tracing."""
        import asyncio

        @track(flush=True)
        async def async_test_function():
            await asyncio.sleep(0.01)
            return "async_result"

        # Test with tracing disabled
        set_tracing_active(False)
        result = asyncio.run(async_test_function())
        assert result == "async_result"
        assert len(fake_backend.trace_trees) == 0

        # Test with tracing enabled
        set_tracing_active(True)
        result = asyncio.run(async_test_function())
        assert result == "async_result"
        assert len(fake_backend.trace_trees) == 1

    def test_integration_trackers_respect_dynamic_tracing(self, fake_backend):
        """Test that integration trackers respect dynamic tracing."""

        # Simulate what integration trackers do
        @track(flush=True)
        def mock_integration_call():
            return "integration_result"

        # With tracing disabled
        set_tracing_active(False)
        result = mock_integration_call()
        assert result == "integration_result"
        assert len(fake_backend.trace_trees) == 0

        # With tracing enabled
        set_tracing_active(True)
        result = mock_integration_call()
        assert result == "integration_result"
        assert len(fake_backend.trace_trees) == 1

    def test_generator_functions_work_with_dynamic_tracing(self, fake_backend):
        """Test that generator functions work with dynamic tracing."""

        @track(flush=True)
        def generator_function():
            yield 1
            yield 2
            yield 3

        # With tracing disabled
        set_tracing_active(False)
        gen = generator_function()
        results = list(gen)
        assert results == [1, 2, 3]
        assert len(fake_backend.trace_trees) == 0

        # With tracing enabled
        set_tracing_active(True)
        gen = generator_function()
        results = list(gen)
        assert results == [1, 2, 3]
        assert len(fake_backend.trace_trees) == 1

    def test_error_handling_with_dynamic_tracing(self, fake_backend):
        """Test that errors are handled correctly with dynamic tracing."""

        @track(flush=True)
        def function_that_raises():
            raise ValueError("Test error")

        # With tracing disabled
        set_tracing_active(False)
        with pytest.raises(ValueError, match="Test error"):
            function_that_raises()
        assert len(fake_backend.trace_trees) == 0

        # With tracing enabled
        set_tracing_active(True)
        with pytest.raises(ValueError, match="Test error"):
            function_that_raises()
        # Should have captured the error in traces
        assert len(fake_backend.trace_trees) == 1
        assert fake_backend.trace_trees[0].error_info is not None
        assert fake_backend.trace_trees[0].error_info["message"] == "Test error"

    def test_multiple_function_calls_with_state_changes(self, fake_backend):
        """Test multiple function calls with tracing state changes."""

        @track(flush=True)
        def test_function():
            return "result"

        # Call 1: Tracing enabled
        set_tracing_active(True)
        test_function()
        assert len(fake_backend.trace_trees) == 1

        # Call 2: Tracing disabled
        set_tracing_active(False)
        test_function()
        # Count should not increase
        assert len(fake_backend.trace_trees) == 1

        # Call 3: Tracing enabled again
        set_tracing_active(True)
        test_function()
        # Count should increase
        assert len(fake_backend.trace_trees) == 2
