"""
Unit tests for streamer and replay manager interaction including lifecycle management.

Tests the integration between the Streamer and ReplayManager, covering:
- Initialization: Streamer sets the replay callback and starts the ReplayManager
- Close lifecycle: Streamer closes the ReplayManager during its own close
- Flush: Streamer conditionally flushes the ReplayManager based on server connection
- Replay callback: Messages replayed by ReplayManager flow back into the Streamer's queue
"""

import datetime
import time
from typing import Generator, Tuple
from unittest import mock

import pytest

from opik.healthcheck import connection_monitor
from opik.message_processing import messages, streamer_constructors, streamer
from opik.message_processing.replay import db_manager, replay_manager


def _create_trace_message(
    message_id: int, trace_id: str = "trace-1"
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


def _make_replay_manager(
    has_connection: bool = True,
    tick_interval: float = 0.05,
) -> replay_manager.ReplayManager:
    """Create a ReplayManager with a real DB but a mock connection monitor."""
    monitor = mock.MagicMock(spec=connection_monitor.OpikConnectionMonitor)
    monitor.has_server_connection = has_connection
    monitor.tick.return_value = connection_monitor.ConnectionStatus.connection_ok
    return replay_manager.ReplayManager(
        monitor=monitor,
        batch_size=10,
        batch_replay_delay=0.01,
        tick_interval_seconds=tick_interval,
    )


@pytest.fixture
def mock_replay_manager() -> mock.MagicMock:
    """A fully mocked ReplayManager for testing Streamer interactions."""
    rm = mock.MagicMock(spec=replay_manager.ReplayManager)
    rm.has_server_connection = True
    # start() and close() must be callables
    rm.start = mock.Mock()
    rm.close = mock.Mock()
    rm.flush = mock.Mock()
    rm.set_replay_callback = mock.Mock()
    return rm


@pytest.fixture
def streamer_with_mock_replay(
    fake_file_upload_manager,
    mock_replay_manager: mock.MagicMock,
) -> Generator[Tuple[streamer.Streamer, mock.MagicMock, mock.Mock], None, None]:
    """Create a Streamer with a mocked ReplayManager and a mock message processor."""
    tested = None
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=False,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=mock_replay_manager,
        )
        yield tested, mock_replay_manager, mock_message_processor
    finally:
        if tested is not None:
            tested.close(timeout=5)


@pytest.fixture
def streamer_with_real_replay(
    fake_file_upload_manager,
) -> Generator[
    Tuple[streamer.Streamer, replay_manager.ReplayManager, mock.Mock], None, None
]:
    """Create a Streamer with a real ReplayManager backed by an in-memory DB."""
    tested = None
    rm = None
    try:
        rm = _make_replay_manager(has_connection=True)
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=False,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=rm,
        )
        yield tested, rm, mock_message_processor
    finally:
        if tested is not None:
            tested.close(timeout=5)
        if rm is not None and rm.is_alive():
            rm.close()
            rm.join(timeout=2)


class TestStreamerReplayManagerInitialization:
    def test_init__sets_replay_callback_on_replay_manager(
        self,
        streamer_with_mock_replay: Tuple[streamer.Streamer, mock.MagicMock, mock.Mock],
    ):
        """Streamer.__init__ must call set_replay_callback with its put method."""
        tested, mock_rm, _ = streamer_with_mock_replay

        mock_rm.set_replay_callback.assert_called_once_with(tested.put)

    def test_init__starts_replay_manager(
        self,
        streamer_with_mock_replay: Tuple[streamer.Streamer, mock.MagicMock, mock.Mock],
    ):
        """Streamer.__init__ must call start() on the ReplayManager."""
        _, mock_rm, _ = streamer_with_mock_replay

        mock_rm.start.assert_called_once()

    def test_init__callback_set_before_start(
        self,
        streamer_with_mock_replay: Tuple[streamer.Streamer, mock.MagicMock, mock.Mock],
    ):
        """set_replay_callback must be called before start()."""
        _, mock_rm, _ = streamer_with_mock_replay

        # Both were called; verify ordering via mock's call list
        calls = mock_rm.method_calls
        callback_idx = next(
            i for i, c in enumerate(calls) if c[0] == "set_replay_callback"
        )
        start_idx = next(i for i, c in enumerate(calls) if c[0] == "start")
        assert callback_idx < start_idx


class TestStreamerCloseLifecycle:
    def test_close__closes_replay_manager(
        self,
        streamer_with_mock_replay: Tuple[streamer.Streamer, mock.MagicMock, mock.Mock],
    ):
        """Streamer.close() must call close() on the ReplayManager."""
        tested, mock_rm, _ = streamer_with_mock_replay

        tested.close(timeout=5)

        mock_rm.close.assert_called_once()

    def test_close__replay_manager_closed_before_queue_consumers(
        self,
        fake_file_upload_manager,
    ):
        """ReplayManager should be closed before queue consumers are shut down."""
        mock_rm = mock.MagicMock(spec=replay_manager.ReplayManager)
        mock_rm.has_server_connection = True

        call_order = []
        mock_rm.close.side_effect = lambda: call_order.append("replay_manager_close")

        mock_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_processor,
            n_consumers=1,
            use_batching=False,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=mock_rm,
        )

        # Patch _close_queue_consumers to track ordering
        original_close_consumers = tested._close_queue_consumers

        def tracked_close_consumers():
            call_order.append("queue_consumers_close")
            original_close_consumers()

        tested._close_queue_consumers = tracked_close_consumers

        tested.close(timeout=5)

        assert "replay_manager_close" in call_order
        assert "queue_consumers_close" in call_order
        rm_idx = call_order.index("replay_manager_close")
        qc_idx = call_order.index("queue_consumers_close")
        assert rm_idx < qc_idx

    def test_close__with_real_replay_manager__thread_stops(
        self,
        streamer_with_real_replay: Tuple[
            streamer.Streamer, replay_manager.ReplayManager, mock.Mock
        ],
    ):
        """After Streamer.close(), the ReplayManager thread should stop."""
        tested, rm, _ = streamer_with_real_replay

        assert rm.is_alive()

        tested.close(timeout=5)
        rm.join(timeout=2)

        assert not rm.is_alive()


class TestStreamerFlushReplayInteraction:
    def test_flush__with_server_connection__flushes_replay_manager(
        self,
        streamer_with_mock_replay: Tuple[streamer.Streamer, mock.MagicMock, mock.Mock],
    ):
        """When has_server_connection is True, flush() should call replay_manager.flush()."""
        tested, mock_rm, _ = streamer_with_mock_replay
        mock_rm.has_server_connection = True
        mock_rm.flush.reset_mock()

        tested.flush(timeout=2)

        mock_rm.flush.assert_called_once()

    def test_flush__without_server_connection__skips_replay_flush(
        self,
        streamer_with_mock_replay: Tuple[streamer.Streamer, mock.MagicMock, mock.Mock],
    ):
        """When has_server_connection is False, flush() should NOT call replay_manager.flush()."""
        tested, mock_rm, _ = streamer_with_mock_replay
        mock_rm.has_server_connection = False
        mock_rm.flush.reset_mock()

        tested.flush(timeout=2)

        mock_rm.flush.assert_not_called()


class TestReplayCallbackFlow:
    def test_replay_callback__replayed_message_reaches_processor(
        self,
        streamer_with_real_replay: Tuple[
            streamer.Streamer, replay_manager.ReplayManager, mock.Mock
        ],
    ):
        """Messages replayed by the ReplayManager should flow through the Streamer
        and reach the message processor."""
        tested, rm, mock_processor = streamer_with_real_replay

        # Register a failed message directly in the DB
        msg = _create_trace_message(message_id=1)
        rm.database_manager.register_message(
            msg, status=db_manager.MessageStatus.failed
        )

        # Wait for the message to be processed (requires connection) to trigger replay by the replay manager
        tested.flush(timeout=5)

        assert mock_processor.process.call_count >= 1

    def test_replay_callback__multiple_messages__all_reach_processor(
        self,
        streamer_with_real_replay: Tuple[
            streamer.Streamer, replay_manager.ReplayManager, mock.Mock
        ],
    ):
        """Multiple replayed messages should all reach the processor."""
        tested, rm, mock_processor = streamer_with_real_replay

        for i in range(5):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            rm.database_manager.register_message(
                msg, status=db_manager.MessageStatus.failed
            )

        tested.flush(timeout=5)

        assert mock_processor.process.call_count >= 5

    def test_put__after_drain__replayed_messages_are_dropped(
        self,
        streamer_with_real_replay: Tuple[
            streamer.Streamer, replay_manager.ReplayManager, mock.Mock
        ],
    ):
        """After close() sets _drain=True, put() should silently discard messages."""
        tested, rm, mock_processor = streamer_with_real_replay

        # Close the streamer to set drain mode
        tested.close(timeout=5)
        mock_processor.process.reset_mock()

        # Now try to put a message (simulates a late replay callback)
        msg = _create_trace_message(message_id=99)
        tested.put(msg)

        # Give a moment for any processing
        time.sleep(0.2)

        mock_processor.process.assert_not_called()


class TestStreamerReplayManagerLifecycleEndToEnd:
    def test_full_lifecycle__init_put_flush_close(
        self,
        streamer_with_real_replay: Tuple[
            streamer.Streamer, replay_manager.ReplayManager, mock.Mock
        ],
    ):
        """Full lifecycle: init → put messages → flush → close.
        Verify the message processor receives the messages put into streamer.
        Verify replay manager is alive during operation and stopped after close."""
        tested, rm, mock_processor = streamer_with_real_replay

        # ReplayManager should be running
        assert rm.is_alive()
        assert rm.database_manager.initialized
        assert not rm.database_manager.closed

        # Put a regular message through the streamer
        msg = _create_trace_message(message_id=1)
        tested.put(msg)

        # Flush to ensure processing
        tested.flush(timeout=5)

        # Processor should have received the message
        assert mock_processor.process.call_count >= 1

        # Close everything
        tested.close(timeout=5)
        rm.join(timeout=2)

        # Verify cleanup
        assert not rm.is_alive()
        assert rm.database_manager.closed

    def test_lifecycle__failed_message_replayed_on_flush(
        self,
        streamer_with_real_replay: Tuple[
            streamer.Streamer, replay_manager.ReplayManager, mock.Mock
        ],
    ):
        """Simulate: message registered → marked failed → flush triggers replay → processor receives it."""
        tested, rm, mock_processor = streamer_with_real_replay

        # 1. Register a message and mark it as failed
        msg = _create_trace_message(message_id=1)
        rm.register_message(msg)
        rm.message_sent_failed(1, failure_reason="server down")

        # Verify it's in a failed state
        db_msg = rm.database_manager.get_db_message(1)
        assert db_msg is not None
        assert db_msg.status == db_manager.MessageStatus.failed

        # 2. Flush the streamer (this flushes the replay manager too since connection is mocked as OK)
        tested.flush(timeout=5)

        # 3. The replayed message should reach the processor
        assert mock_processor.process.call_count >= 1

        # 4. Clean up
        tested.close(timeout=5)
        rm.join(timeout=2)

        assert not rm.is_alive()
