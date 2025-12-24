"""
Tests for the CancellationChecker class.

These tests verify that:
1. The cancellation checker correctly detects Redis cancellation signals
2. The background polling thread works correctly
3. The callback is invoked when cancellation is detected
"""

import threading
import time
import uuid
from unittest.mock import MagicMock, patch

import pytest

from opik_backend.studio.cancellation import CancellationChecker


class TestCancellationChecker:
    """Tests for CancellationChecker."""

    @pytest.fixture(autouse=True)
    def reset_redis_client(self):
        """Reset the global Redis client before each test."""
        import opik_backend.utils.redis_utils as redis_utils
        redis_utils._redis_client = None
        yield
        redis_utils._redis_client = None

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        mock_client = MagicMock()
        with patch(
            "opik_backend.utils.redis_utils._create_redis_client_from_env",
            return_value=mock_client
        ):
            yield mock_client

    @pytest.fixture
    def optimization_id(self):
        """Generate a random optimization ID."""
        return str(uuid.uuid4())

    def test_check_cancelled_returns_false_when_no_signal(self, mock_redis, optimization_id):
        """Test that check_cancelled returns False when no cancellation signal exists."""
        mock_redis.exists.return_value = False

        checker = CancellationChecker(optimization_id)
        result = checker.check_cancelled()

        assert result is False
        mock_redis.exists.assert_called_once_with(f"opik:cancel:{optimization_id}")

    def test_check_cancelled_returns_true_when_signal_exists(self, mock_redis, optimization_id):
        """Test that check_cancelled returns True when cancellation signal exists."""
        mock_redis.exists.return_value = True

        checker = CancellationChecker(optimization_id)
        result = checker.check_cancelled()

        assert result is True
        mock_redis.exists.assert_called_once_with(f"opik:cancel:{optimization_id}")

    def test_background_check_invokes_callback_on_cancellation(self, mock_redis, optimization_id):
        """Test that background check invokes callback when cancellation is detected."""
        # First call returns False, second call returns True (simulating cancellation)
        mock_redis.exists.side_effect = [False, True]

        checker = CancellationChecker(optimization_id)
        callback_called = threading.Event()

        def on_cancelled():
            callback_called.set()

        checker.start_background_check(on_cancelled=on_cancelled, interval_secs=0.1)

        # Wait for callback to be called (with timeout)
        callback_was_called = callback_called.wait(timeout=2)

        checker.stop_background_check()

        assert callback_was_called, "Callback should have been invoked"

    def test_background_check_stops_after_cancellation(self, mock_redis, optimization_id):
        """Test that background check stops polling after cancellation is detected."""
        mock_redis.exists.return_value = True

        checker = CancellationChecker(optimization_id)
        callback_called = threading.Event()

        def on_cancelled():
            callback_called.set()

        checker.start_background_check(on_cancelled=on_cancelled, interval_secs=0.1)

        # Wait for callback
        callback_called.wait(timeout=2)

        # Give thread time to stop
        time.sleep(0.3)

        # Thread should have stopped
        assert checker._thread is None or not checker._thread.is_alive()

    def test_stop_background_check_stops_running_thread(self, mock_redis, optimization_id):
        """Test that stop_background_check stops a running thread."""
        mock_redis.exists.return_value = False  # Never cancelled

        checker = CancellationChecker(optimization_id)
        callback = MagicMock()

        checker.start_background_check(on_cancelled=callback, interval_secs=0.1)

        # Verify thread is running
        assert checker._thread is not None
        assert checker._thread.is_alive()

        # Stop the thread
        checker.stop_background_check()

        # Give thread time to stop
        time.sleep(0.2)

        # Thread should be stopped
        assert checker._thread is None or not checker._thread.is_alive()

        # Callback should not have been called
        callback.assert_not_called()

    def test_start_background_check_ignores_duplicate_starts(self, mock_redis, optimization_id):
        """Test that starting background check twice doesn't create multiple threads."""
        mock_redis.exists.return_value = False

        checker = CancellationChecker(optimization_id)
        callback = MagicMock()

        checker.start_background_check(on_cancelled=callback, interval_secs=0.1)
        first_thread = checker._thread

        # Try to start again
        checker.start_background_check(on_cancelled=callback, interval_secs=0.1)
        second_thread = checker._thread

        # Should be the same thread
        assert first_thread is second_thread

        checker.stop_background_check()

    def test_cancel_key_pattern(self, optimization_id):
        """Test that the cancel key pattern is correct."""
        expected_key = f"opik:cancel:{optimization_id}"
        assert CancellationChecker.CANCEL_KEY_PATTERN.format(optimization_id) == expected_key

    def test_background_check_handles_redis_errors_gracefully(self, mock_redis, optimization_id):
        """Test that background check continues polling even if Redis throws an error."""
        # First call raises error, second call returns True (cancelled)
        mock_redis.exists.side_effect = [Exception("Redis connection error"), True]

        checker = CancellationChecker(optimization_id)
        callback_called = threading.Event()

        def on_cancelled():
            callback_called.set()

        checker.start_background_check(on_cancelled=on_cancelled, interval_secs=0.1)

        # Should still detect cancellation after error
        callback_was_called = callback_called.wait(timeout=2)

        checker.stop_background_check()

        assert callback_was_called, "Callback should have been invoked after Redis error"
