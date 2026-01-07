"""
Tests for the CancellationMonitor and CancellationHandle classes.

These tests verify that:
1. The centralized monitor correctly detects Redis cancellation signals via MGET
2. Multiple optimizations can be monitored simultaneously
3. Callbacks are invoked when cancellation is detected
4. The context manager works correctly for CancellationHandle
5. The was_cancelled property is thread-safe
6. Redis key cleanup works correctly
"""

import threading
import time
import uuid
from unittest.mock import MagicMock, patch

import pytest

import opik_backend.utils.redis_utils as redis_utils
from opik_backend.studio.cancellation import (
    CancellationHandle,
    CancellationMonitor,
    CANCEL_KEY_PATTERN,
    ENV_CANCEL_POLL_INTERVAL_SECS,
)

# Use a short poll interval for faster tests (in seconds, supports float)
TEST_POLL_INTERVAL = "0.1"


class TestCancellationMonitor:
    """Tests for CancellationMonitor singleton."""

    @pytest.fixture(autouse=True)
    def reset_singletons(self, monkeypatch):
        """Reset singletons and Redis client before each test."""
        # Use short poll interval for tests
        monkeypatch.setenv(ENV_CANCEL_POLL_INTERVAL_SECS, TEST_POLL_INTERVAL)
        
        # Stop any existing monitor thread
        if CancellationMonitor._instance is not None:
            try:
                CancellationMonitor._instance._stop_event.set()
                if CancellationMonitor._instance._thread:
                    CancellationMonitor._instance._thread.join(timeout=1)
            except Exception:
                pass
        
        redis_utils._redis_client = None
        CancellationMonitor._instance = None
        yield
        
        # Cleanup after test
        if CancellationMonitor._instance is not None:
            try:
                CancellationMonitor._instance._stop_event.set()
                if CancellationMonitor._instance._thread:
                    CancellationMonitor._instance._thread.join(timeout=1)
            except Exception:
                pass
        
        redis_utils._redis_client = None
        CancellationMonitor._instance = None

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        mock_client = MagicMock()
        mock_client.mget.return_value = []  # Default: no cancellations
        with patch(
            "opik_backend.utils.redis_utils._create_redis_client_from_env",
            return_value=mock_client
        ):
            yield mock_client

    @pytest.fixture
    def optimization_id(self):
        """Generate a random optimization ID."""
        return str(uuid.uuid4())

    def test_monitor_is_singleton(self, mock_redis):
        """Test that CancellationMonitor is a singleton."""
        monitor1 = CancellationMonitor()
        monitor2 = CancellationMonitor()
        assert monitor1 is monitor2

    def test_register_starts_monitor_thread(self, mock_redis, optimization_id):
        """Test that registering starts the monitor thread."""
        monitor = CancellationMonitor()
        callback = MagicMock()
        
        monitor.register(optimization_id, callback)
        
        # Give thread time to start
        time.sleep(0.1)
        
        assert monitor._thread is not None
        assert monitor._thread.is_alive()
        
        monitor.unregister(optimization_id)
        time.sleep(0.2)

    def test_unregister_stops_monitor_when_empty(self, mock_redis, optimization_id):
        """Test that unregistering the last optimization stops the monitor thread."""
        monitor = CancellationMonitor()
        callback = MagicMock()
        
        monitor.register(optimization_id, callback)
        time.sleep(0.1)
        
        assert monitor._thread is not None
        
        monitor.unregister(optimization_id)
        time.sleep(0.3)
        
        assert monitor._thread is None or not monitor._thread.is_alive()

    def test_mget_called_with_all_registered_keys(self, mock_redis):
        """Test that MGET is called with all registered optimization keys."""
        monitor = CancellationMonitor()
        
        opt_id1 = str(uuid.uuid4())
        opt_id2 = str(uuid.uuid4())
        
        monitor.register(opt_id1, MagicMock())
        monitor.register(opt_id2, MagicMock())
        
        # Wait for at least one poll cycle
        time.sleep(0.3)
        
        # Check that mget was called
        assert mock_redis.mget.called
        
        # Collect all keys from all mget calls
        all_keys_checked = set()
        for call in mock_redis.mget.call_args_list:
            keys = call[0][0]
            all_keys_checked.update(keys)
        
        expected_keys = [
            CANCEL_KEY_PATTERN.format(opt_id1),
            CANCEL_KEY_PATTERN.format(opt_id2),
        ]
        
        # Both keys should have been checked at some point
        for key in expected_keys:
            assert key in all_keys_checked, f"Key {key} was never checked. Checked: {all_keys_checked}"
        
        monitor.unregister(opt_id1)
        monitor.unregister(opt_id2)
        time.sleep(0.2)

    def test_callback_invoked_on_cancellation(self, mock_redis, optimization_id):
        """Test that callback is invoked when cancellation is detected."""
        # First MGET returns no cancellation, second returns cancellation
        mock_redis.mget.side_effect = [
            [None],  # No cancellation
            [b"1"],  # Cancelled
        ]
        
        monitor = CancellationMonitor()
        callback_called = threading.Event()
        
        def on_cancelled():
            callback_called.set()
        
        monitor.register(optimization_id, on_cancelled)
        
        # Wait for callback
        callback_was_called = callback_called.wait(timeout=2)
        
        assert callback_was_called, "Callback should have been invoked"
        
        time.sleep(0.2)

    def test_redis_key_deleted_after_cancellation(self, mock_redis, optimization_id):
        """Test that Redis key is deleted after cancellation is detected."""
        mock_redis.mget.return_value = [b"1"]  # Cancelled
        
        monitor = CancellationMonitor()
        callback_called = threading.Event()
        
        monitor.register(optimization_id, lambda: callback_called.set())
        
        callback_called.wait(timeout=2)
        time.sleep(0.2)
        
        # Key should have been deleted
        expected_key = CANCEL_KEY_PATTERN.format(optimization_id)
        mock_redis.delete.assert_called_with(expected_key)


class TestCancellationHandle:
    """Tests for CancellationHandle."""

    @pytest.fixture(autouse=True)
    def reset_singletons(self, monkeypatch):
        """Reset singletons and Redis client before each test."""
        # Use short poll interval for tests
        monkeypatch.setenv(ENV_CANCEL_POLL_INTERVAL_SECS, TEST_POLL_INTERVAL)
        
        # Stop any existing monitor thread
        if CancellationMonitor._instance is not None:
            try:
                CancellationMonitor._instance._stop_event.set()
                if CancellationMonitor._instance._thread:
                    CancellationMonitor._instance._thread.join(timeout=1)
            except Exception:
                pass
        
        redis_utils._redis_client = None
        CancellationMonitor._instance = None
        yield
        
        # Cleanup after test
        if CancellationMonitor._instance is not None:
            try:
                CancellationMonitor._instance._stop_event.set()
                if CancellationMonitor._instance._thread:
                    CancellationMonitor._instance._thread.join(timeout=1)
            except Exception:
                pass
        
        redis_utils._redis_client = None
        CancellationMonitor._instance = None

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        mock_client = MagicMock()
        mock_client.mget.return_value = []
        with patch(
            "opik_backend.utils.redis_utils._create_redis_client_from_env",
            return_value=mock_client
        ):
            yield mock_client

    @pytest.fixture
    def optimization_id(self):
        """Generate a random optimization ID."""
        return str(uuid.uuid4())

    def test_was_cancelled_returns_false_initially(self, mock_redis, optimization_id):
        """Test that was_cancelled returns False before cancellation."""
        handle = CancellationHandle(optimization_id)
        assert handle.was_cancelled is False

    def test_was_cancelled_returns_true_after_cancellation(self, mock_redis, optimization_id):
        """Test that was_cancelled returns True after cancellation is detected."""
        mock_redis.mget.return_value = [b"1"]  # Cancelled
        
        callback_called = threading.Event()
        handle = CancellationHandle(optimization_id, on_cancelled=lambda: callback_called.set())
        handle.register()
        
        callback_called.wait(timeout=2)
        
        assert handle.was_cancelled is True
        
        handle.unregister()
        time.sleep(0.2)

    def test_context_manager_registers_and_unregisters(self, mock_redis, optimization_id):
        """Test that context manager registers on enter and unregisters on exit."""
        mock_redis.mget.return_value = [None]  # No cancellation
        
        with CancellationHandle(optimization_id) as handle:
            assert handle._registered is True
            time.sleep(0.1)
        
        assert handle._registered is False
        time.sleep(0.2)

    def test_context_manager_returns_handle_instance(self, mock_redis, optimization_id):
        """Test that context manager returns the handle instance."""
        with CancellationHandle(optimization_id) as handle:
            assert isinstance(handle, CancellationHandle)
            assert handle.optimization_id == optimization_id

    def test_callback_invoked_through_handle(self, mock_redis, optimization_id):
        """Test that callback passed to handle is invoked on cancellation."""
        mock_redis.mget.return_value = [b"1"]  # Cancelled
        
        callback_called = threading.Event()
        
        with CancellationHandle(optimization_id, on_cancelled=lambda: callback_called.set()) as handle:
            callback_was_called = callback_called.wait(timeout=2)
        
        assert callback_was_called, "Callback should have been invoked"
        time.sleep(0.2)

    def test_multiple_handles_monitored_together(self, mock_redis):
        """Test that multiple handles are monitored by the same monitor thread."""
        opt_id1 = str(uuid.uuid4())
        opt_id2 = str(uuid.uuid4())
        
        # Return cancellation for second optimization only
        def mget_side_effect(keys):
            result = []
            for key in keys:
                if opt_id2 in key:
                    result.append(b"1")  # Cancelled
                else:
                    result.append(None)  # Not cancelled
            return result
        
        mock_redis.mget.side_effect = mget_side_effect
        
        callback1_called = threading.Event()
        callback2_called = threading.Event()
        
        handle1 = CancellationHandle(opt_id1, on_cancelled=lambda: callback1_called.set())
        handle2 = CancellationHandle(opt_id2, on_cancelled=lambda: callback2_called.set())
        
        handle1.register()
        handle2.register()
        
        # Wait for callback2 (should be called)
        callback2_was_called = callback2_called.wait(timeout=2)
        
        # callback1 should NOT be called
        callback1_was_called = callback1_called.wait(timeout=0.5)
        
        assert callback2_was_called, "Callback2 should have been invoked"
        assert not callback1_was_called, "Callback1 should NOT have been invoked"
        
        handle1.unregister()
        handle2.unregister()
        time.sleep(0.2)

    def test_handle_handles_redis_errors_gracefully(self, mock_redis, optimization_id):
        """Test that handle continues working even if Redis throws an error."""
        # First call raises error, subsequent calls return cancellation
        error_raised = [False]
        
        def mget_side_effect(keys):
            if not error_raised[0]:
                error_raised[0] = True
                raise Exception("Redis connection error")
            return [b"1"]  # Cancelled
        
        mock_redis.mget.side_effect = mget_side_effect
        
        callback_called = threading.Event()
        
        with CancellationHandle(optimization_id, on_cancelled=lambda: callback_called.set()) as handle:
            callback_was_called = callback_called.wait(timeout=3)
        
        assert callback_was_called, "Callback should have been invoked after Redis error"
        time.sleep(0.2)


class TestCancellationStress:
    """Stress tests for concurrent cancellation monitoring."""

    @pytest.fixture(autouse=True)
    def reset_singletons(self, monkeypatch):
        """Reset singletons and Redis client before each test."""
        # Use short poll interval for tests
        monkeypatch.setenv(ENV_CANCEL_POLL_INTERVAL_SECS, TEST_POLL_INTERVAL)
        
        # Stop any existing monitor thread
        if CancellationMonitor._instance is not None:
            try:
                CancellationMonitor._instance._stop_event.set()
                if CancellationMonitor._instance._thread:
                    CancellationMonitor._instance._thread.join(timeout=1)
            except Exception:
                pass
        
        redis_utils._redis_client = None
        CancellationMonitor._instance = None
        yield
        
        # Cleanup after test
        if CancellationMonitor._instance is not None:
            try:
                CancellationMonitor._instance._stop_event.set()
                if CancellationMonitor._instance._thread:
                    CancellationMonitor._instance._thread.join(timeout=1)
            except Exception:
                pass
        
        redis_utils._redis_client = None
        CancellationMonitor._instance = None

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        mock_client = MagicMock()
        mock_client.mget.return_value = []
        with patch(
            "opik_backend.utils.redis_utils._create_redis_client_from_env",
            return_value=mock_client
        ):
            yield mock_client

    def test_many_concurrent_optimizations_with_selective_cancellation(self, mock_redis):
        """
        Test many concurrent optimizations where only some are cancelled.
        
        Simulates a realistic scenario:
        - 10 optimizations running concurrently
        - Only 3 are cancelled (opt 2, 5, 8)
        - Verifies only cancelled ones trigger callbacks
        - Verifies non-cancelled ones continue running
        """
        num_optimizations = 10
        cancelled_indices = {2, 5, 8}  # Which optimizations to cancel
        
        opt_ids = [str(uuid.uuid4()) for _ in range(num_optimizations)]
        callbacks_called = {i: threading.Event() for i in range(num_optimizations)}
        handles = []
        
        # Mock MGET to return cancellation signals only for specific optimizations
        def mget_side_effect(keys):
            results = []
            for key in keys:
                # Find which optimization this key belongs to
                cancelled = False
                for i, opt_id in enumerate(opt_ids):
                    if opt_id in key and i in cancelled_indices:
                        cancelled = True
                        break
                results.append(b"1" if cancelled else None)
            return results
        
        mock_redis.mget.side_effect = mget_side_effect
        
        # Register all optimizations
        for i in range(num_optimizations):
            handle = CancellationHandle(
                opt_ids[i],
                on_cancelled=lambda idx=i: callbacks_called[idx].set()
            )
            handle.register()
            handles.append(handle)
        
        # Wait for cancelled ones to be detected
        time.sleep(0.5)
        
        # Verify only cancelled optimizations triggered callbacks
        for i in range(num_optimizations):
            if i in cancelled_indices:
                assert callbacks_called[i].is_set(), f"Optimization {i} should have been cancelled"
            else:
                assert not callbacks_called[i].is_set(), f"Optimization {i} should NOT have been cancelled"
        
        # Cleanup
        for handle in handles:
            handle.unregister()
        time.sleep(0.2)

    def test_rapid_registration_and_unregistration(self, mock_redis):
        """
        Test rapid registration and unregistration of optimizations.
        
        Simulates optimizations starting and completing quickly.
        """
        mock_redis.mget.return_value = []  # No cancellations
        
        num_cycles = 20
        
        for cycle in range(num_cycles):
            opt_id = str(uuid.uuid4())
            handle = CancellationHandle(opt_id)
            handle.register()
            
            # Simulate some work
            time.sleep(0.01)
            
            handle.unregister()
        
        # Monitor should have stopped (no active registrations)
        time.sleep(0.3)
        monitor = CancellationMonitor()
        assert monitor._thread is None or not monitor._thread.is_alive()

    def test_staggered_cancellations_over_time(self, mock_redis):
        """
        Test cancellations that happen at different times.
        
        - 5 optimizations start
        - Cancellations arrive staggered (one per poll cycle)
        """
        num_optimizations = 5
        opt_ids = [str(uuid.uuid4()) for _ in range(num_optimizations)]
        callbacks_called = {i: threading.Event() for i in range(num_optimizations)}
        cancellation_order = []
        
        # Track which poll cycle we're on
        poll_count = [0]
        
        def mget_side_effect(keys):
            poll_count[0] += 1
            results = []
            
            # Cancel one optimization per poll cycle
            cancel_index = poll_count[0] - 1  # 0-indexed
            
            for key in keys:
                cancelled = False
                for i, opt_id in enumerate(opt_ids):
                    if opt_id in key and i == cancel_index:
                        cancelled = True
                        break
                results.append(b"1" if cancelled else None)
            return results
        
        mock_redis.mget.side_effect = mget_side_effect
        
        # Register all optimizations
        handles = []
        for i in range(num_optimizations):
            def make_callback(idx):
                def callback():
                    callbacks_called[idx].set()
                    cancellation_order.append(idx)
                return callback
            
            handle = CancellationHandle(opt_ids[i], on_cancelled=make_callback(i))
            handle.register()
            handles.append(handle)
        
        # Wait for all to be cancelled (should take ~5 poll cycles)
        all_cancelled = all(
            callbacks_called[i].wait(timeout=3)
            for i in range(num_optimizations)
        )
        
        assert all_cancelled, "All optimizations should have been cancelled"
        
        # Verify cancellations happened in order (0, 1, 2, 3, 4)
        assert cancellation_order == list(range(num_optimizations)), \
            f"Cancellations should happen in order, got: {cancellation_order}"
        
        # Cleanup
        for handle in handles:
            handle.unregister()
        time.sleep(0.2)

    def test_concurrent_registration_from_multiple_threads(self, mock_redis):
        """
        Test thread-safety of registration from multiple threads.
        
        Simulates multiple worker threads registering optimizations simultaneously.
        """
        mock_redis.mget.return_value = []  # No cancellations
        
        num_threads = 5
        registrations_per_thread = 10
        all_handles = []
        handles_lock = threading.Lock()
        errors = []
        
        def register_optimizations(thread_id):
            try:
                for i in range(registrations_per_thread):
                    opt_id = f"thread-{thread_id}-opt-{i}"
                    handle = CancellationHandle(opt_id)
                    handle.register()
                    
                    with handles_lock:
                        all_handles.append(handle)
                    
                    time.sleep(0.01)  # Small delay to interleave
            except Exception as e:
                errors.append(e)
        
        # Start multiple threads
        threads = []
        for t in range(num_threads):
            thread = threading.Thread(target=register_optimizations, args=(t,))
            threads.append(thread)
            thread.start()
        
        # Wait for all threads
        for thread in threads:
            thread.join(timeout=5)
        
        # No errors should have occurred
        assert not errors, f"Errors during concurrent registration: {errors}"
        
        # All handles should be registered
        assert len(all_handles) == num_threads * registrations_per_thread
        
        # Cleanup
        for handle in all_handles:
            handle.unregister()
        time.sleep(0.3)
