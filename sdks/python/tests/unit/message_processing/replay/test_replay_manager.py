import datetime
import threading
import time
from typing import Generator, List, Tuple, Optional
from unittest import mock

import pytest

from opik.healthcheck import connection_monitor
from opik.message_processing import messages
from opik.message_processing.replay import db_manager, replay_manager


def _create_trace_message(
    message_id: Optional[int], trace_id: str = "trace-1"
) -> messages.CreateTraceMessage:
    msg = messages.CreateTraceMessage(
        trace_id=trace_id,
        project_name="test-project",
        name="test-trace",
        start_time=datetime.datetime(2024, 1, 1, 12, 0, 0),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1),
        input={"query": "test"},
        output={"answer": "response"},
        metadata=None,
        tags=None,
        error_info=None,
        thread_id=None,
        last_updated_at=None,
    )
    msg.message_id = message_id
    return msg


def _make_monitor(
    tick_return: connection_monitor.ConnectionStatus = connection_monitor.ConnectionStatus.connection_ok,
) -> mock.MagicMock:
    """Create a mock OpikConnectionMonitor with configurable tick() return."""
    monitor = mock.MagicMock(spec=connection_monitor.OpikConnectionMonitor)
    monitor.tick.return_value = tick_return
    monitor.has_server_connection = True
    return monitor


def _make_manager(
    monitor: mock.MagicMock,
    tick_interval: float = 0.05,
) -> replay_manager.ReplayManager:
    """Create a ReplayManager with a fast tick for testing."""
    return replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=10,
        batch_replay_delay=0.01,
        tick_interval_seconds=tick_interval,
    )


@pytest.fixture
def monitor() -> mock.MagicMock:
    return _make_monitor()


@pytest.fixture
def manager(
    monitor: mock.MagicMock,
) -> Generator[replay_manager.ReplayManager, None, None]:
    rm = _make_manager(monitor)
    yield rm
    rm.close()
    if rm.is_alive():
        rm.join(timeout=2)


@pytest.fixture
def manager_monitor(
    monitor: mock.MagicMock,
) -> Generator[
    Tuple[replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor],
    None,
    None,
]:
    rm = _make_manager(monitor, tick_interval=0.05)
    yield rm, monitor
    rm.close()
    if rm.is_alive():
        rm.join(timeout=2)


class TestReplayManagerInitialization:
    def test_init__default_parameters__creates_thread(
        self, manager: replay_manager.ReplayManager
    ):
        assert manager.daemon is True
        assert manager.name == "ReplayManager"
        assert not manager.is_alive()

    def test_init_db_manager_initialized(self, manager: replay_manager.ReplayManager):
        assert manager.database_manager.initialized
        assert not manager.database_manager.closed


class TestStart:
    def test_start__without_callback__raises_value_error(
        self, manager: replay_manager.ReplayManager
    ):
        with pytest.raises(ValueError, match="Replay callback must be set"):
            manager.start()

    def test_start__with_callback__thread_starts(
        self, manager: replay_manager.ReplayManager
    ):
        manager.set_replay_callback(mock.Mock())
        manager.start()

        assert manager.is_alive()

        manager.close()
        manager.join(timeout=2)
        assert not manager.is_alive()


class TestClose:
    def test_close__running_thread__stops_loop(
        self, manager: replay_manager.ReplayManager
    ):
        manager.set_replay_callback(mock.Mock())
        manager.start()
        assert manager.is_alive()

        manager.close()
        manager.join(timeout=2)

        assert not manager.is_alive()

    def test_close_db_manager_closed_after_thread_exits(
        self, manager: replay_manager.ReplayManager
    ):
        manager.set_replay_callback(mock.Mock())
        manager.start()

        manager.close()
        manager.join(timeout=2)

        assert manager.database_manager.closed

    def test_close__interruptible_sleep(self, monitor: mock.MagicMock):
        """close() should interrupt the sleep and stop quickly, not wait full tick."""
        rm = _make_manager(monitor, tick_interval=5.0)
        rm.set_replay_callback(mock.Mock())
        rm.start()

        start_time = time.time()
        rm.close()
        rm.join(timeout=2)
        elapsed = time.time() - start_time

        assert elapsed < 2.0, f"close() took {elapsed:.2f}s, should be near-instant"


class TestHasServerConnection:
    def test_has_server_connection__delegates_to_monitor(
        self, manager: replay_manager.ReplayManager, monitor: mock.MagicMock
    ):
        monitor.has_server_connection = True
        assert manager.has_server_connection is True

        monitor.has_server_connection = False
        assert manager.has_server_connection is False


class TestRegisterMessage:
    def test_register_message__stores_in_db(
        self, manager: replay_manager.ReplayManager
    ):
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg)

        db_msg = manager.database_manager.get_db_message(1)
        assert db_msg is not None
        assert db_msg.id == 1
        assert db_msg.status == db_manager.MessageStatus.registered

    def test_register_message__automatically_set_message_id(
        self, manager: replay_manager.ReplayManager
    ):
        msg = _create_trace_message(message_id=None)
        manager.register_message(
            msg
        )  # this will set msg.message_id to a particular value

        assert msg.message_id is not None

        db_msg = manager.database_manager.get_db_message(msg.message_id)
        assert db_msg is not None
        assert db_msg.id == msg.message_id
        assert db_msg.status == db_manager.MessageStatus.registered


class TestUnregisterMessage:
    def test_unregister_message__deletes_from_db(
        self, manager: replay_manager.ReplayManager
    ):
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg)

        manager.unregister_message(1)

        db_msg = manager.database_manager.get_db_message(1)
        assert db_msg is None


class TestMessageSentFailed:
    def test_message_sent_failed__marks_message_as_failed(
        self, manager: replay_manager.ReplayManager
    ):
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg)

        manager.message_sent_failed(1, failure_reason="timeout")

        db_msg = manager.database_manager.get_db_message(1)
        assert db_msg is not None
        assert db_msg.status == db_manager.MessageStatus.failed

    def test_message_sent_failed__notifies_monitor(
        self, manager: replay_manager.ReplayManager, monitor: mock.MagicMock
    ):
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg)

        manager.message_sent_failed(1, failure_reason="timeout")

        monitor.connection_failed.assert_called_once_with(failure_reason="timeout")


class TestFlush:
    def test_flush__without_callback__raises_value_error(
        self, manager: replay_manager.ReplayManager
    ):
        with pytest.raises(ValueError, match="Replay callback must be set"):
            manager.flush()

    def test_flush__no_failed_messages__callback_not_called(
        self, manager: replay_manager.ReplayManager
    ):
        callback = mock.Mock()
        manager.set_replay_callback(callback)

        manager.flush()

        callback.assert_not_called()

    def test_flush__with_failed_messages__replays_all(
        self, manager: replay_manager.ReplayManager
    ):
        for i in range(3):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.database_manager.register_message(
                msg, status=db_manager.MessageStatus.failed
            )

        callback = mock.Mock()
        manager.set_replay_callback(callback)

        manager.flush()

        assert callback.call_count == 3
        for call_args in callback.call_args_list:
            replayed_msg = call_args[0][0]
            assert isinstance(replayed_msg, messages.CreateTraceMessage)

    def test_flush__callback_failure__re_marks_message_as_failed(
        self, manager: replay_manager.ReplayManager
    ):
        msg = _create_trace_message(message_id=1)
        manager.database_manager.register_message(
            msg, status=db_manager.MessageStatus.failed
        )

        callback = mock.Mock(side_effect=RuntimeError("send error"))
        manager.set_replay_callback(callback)

        manager.flush()

        db_msg = manager.database_manager.get_db_message(1)
        assert db_msg is not None
        assert db_msg.status == db_manager.MessageStatus.failed


class TestLoopConnectionRestored:
    def test_loop__connection_restored__replays_failed_messages(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        rm, monitor = manager_monitor
        monitor.tick.return_value = (
            connection_monitor.ConnectionStatus.connection_restored
        )

        # Register a failed message
        msg = _create_trace_message(message_id=1)
        rm.database_manager.register_message(
            msg, status=db_manager.MessageStatus.failed
        )

        replayed: List[messages.BaseMessage] = []

        def callback(m: messages.BaseMessage) -> None:
            replayed.append(m)

        rm.set_replay_callback(callback)
        rm.start()

        # Wait for at least one tick cycle
        deadline = time.time() + 0.5
        while not replayed and time.time() < deadline:
            time.sleep(0.05)

        rm.close()
        rm.join(timeout=2)

        assert len(replayed) == 1
        assert isinstance(replayed[0], messages.CreateTraceMessage)
        assert replayed[0].trace_id == "trace-1"

    def test_loop__connection_restored__resets_monitor(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        rm, monitor = manager_monitor
        monitor.tick.return_value = (
            connection_monitor.ConnectionStatus.connection_restored
        )

        rm.set_replay_callback(mock.Mock())
        rm.start()

        # Wait for at least one tick
        deadline = time.time() + 2.0
        while monitor.reset.call_count == 0 and time.time() < deadline:
            time.sleep(0.05)

        rm.close()
        rm.join(timeout=2)

        monitor.reset.assert_called()

    def test_loop__connection_ok__no_replay(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        rm, monitor = manager_monitor
        monitor.tick.return_value = connection_monitor.ConnectionStatus.connection_ok

        msg = _create_trace_message(message_id=1)
        rm.database_manager.register_message(
            msg, status=db_manager.MessageStatus.failed
        )

        callback = mock.Mock()
        rm.set_replay_callback(callback)
        rm.start()

        # Let several ticks pass
        time.sleep(0.1)

        rm.close()
        rm.join(timeout=2)

        callback.assert_not_called()

    def test_loop__connection_failed__no_replay(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        rm, monitor = manager_monitor
        monitor.tick.return_value = (
            connection_monitor.ConnectionStatus.connection_failed
        )

        msg = _create_trace_message(message_id=1)
        rm.database_manager.register_message(
            msg, status=db_manager.MessageStatus.failed
        )

        callback = mock.Mock()
        rm.set_replay_callback(callback)
        rm.start()

        time.sleep(0.1)

        rm.close()
        rm.join(timeout=2)

        callback.assert_not_called()


class TestLoopExceptionHandling:
    def test_loop__tick_raises__thread_continues(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        """The loop should survive exceptions from monitor.tick() and keep running."""
        call_count = 0

        def tick_side_effect():
            nonlocal call_count
            call_count += 1
            if call_count <= 2:
                raise ConnectionError("probe failed")
            return connection_monitor.ConnectionStatus.connection_ok

        rm, monitor = manager_monitor
        monitor.tick.side_effect = tick_side_effect

        rm.set_replay_callback(mock.Mock())
        rm.start()

        # Wait for at least 3 ticks
        deadline = time.time() + 1.0
        while call_count < 3 and time.time() < deadline:
            time.sleep(0.05)

        rm.close()
        rm.join(timeout=2)

        assert call_count >= 3, "Thread should have survived the first two exceptions"

    def test_loop__replay_callback_raises__thread_continues(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        """If replay_failed_messages raises, the loop should continue."""
        rm, monitor = manager_monitor

        msg = _create_trace_message(message_id=1)
        rm.database_manager.register_message(
            msg, status=db_manager.MessageStatus.failed
        )
        # mark connection as restored to enable replay callback
        monitor.tick.return_value = (
            connection_monitor.ConnectionStatus.connection_restored
        )

        call_count = 0

        def failing_callback(m: messages.BaseMessage) -> None:
            nonlocal call_count
            call_count += 1
            raise RuntimeError("replay error")

        rm.set_replay_callback(failing_callback)
        rm.start()

        # Wait for a few ticks
        deadline = time.time() + 2.0
        while call_count < 2 and time.time() < deadline:
            time.sleep(0.05)

        rm.close()
        rm.join(timeout=2)

        assert call_count >= 2, "Loop should have continued despite replay errors"

        assert rm.is_alive() is False, "Thread should have exited cleanly"


class TestFlushConcurrency:
    def test_flush_and_loop__serialized_by_replay_lock(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        """flush() and the loop both acquire _replay_lock, so only one replay
        can happen at a time."""
        rm, monitor = manager_monitor

        for i in range(5):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            rm.database_manager.register_message(
                msg, status=db_manager.MessageStatus.failed
            )

        replayed_ids: List[str] = []
        lock = threading.Lock()

        def callback(m: messages.BaseMessage) -> None:
            with lock:
                assert isinstance(m, messages.CreateTraceMessage)
                replayed_ids.append(m.trace_id)

        rm.set_replay_callback(callback)
        rm.start()

        # Also call flush from the main thread
        rm.flush()

        # Let the loop run too
        time.sleep(0.3)

        rm.close()
        rm.join(timeout=2)

        # Each message should appear at least once (no corruption)
        assert len(replayed_ids) == 5


class TestEndToEnd:
    def test_full_lifecycle__register_fail_restore_replay(
        self,
        manager_monitor: Tuple[
            replay_manager.ReplayManager, connection_monitor.OpikConnectionMonitor
        ],
    ):
        """Test the complete message lifecycle:
        register → fail → connection restored → replay → delivered."""
        rm, monitor = manager_monitor

        replayed: List[messages.BaseMessage] = []

        def callback(m: messages.BaseMessage) -> None:
            replayed.append(m)

        rm.set_replay_callback(callback)

        # 1. Register a message
        msg = _create_trace_message(message_id=1)
        rm.register_message(msg)

        # 2. Mark it as failed (simulating a connection error during sending)
        rm.message_sent_failed(1, failure_reason="connection timeout")
        db_msg = rm.database_manager.get_db_message(1)
        assert db_msg is not None
        assert db_msg.status == db_manager.MessageStatus.failed

        # 3. Start the thread — connection is still failed, no replay
        rm.start()
        time.sleep(0.2)
        assert len(replayed) == 0

        # 4. Simulate connection restored
        monitor.tick.return_value = (
            connection_monitor.ConnectionStatus.connection_restored
        )

        # 5. Wait for replay
        deadline = time.time() + 2.0
        while not replayed and time.time() < deadline:
            time.sleep(0.05)

        rm.close()
        rm.join(timeout=2)

        assert len(replayed) == 1
        assert isinstance(replayed[0], messages.CreateTraceMessage)
        assert replayed[0].trace_id == "trace-1"
        monitor.reset.assert_called()
