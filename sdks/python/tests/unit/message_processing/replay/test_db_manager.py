import datetime
import sqlite3
from typing import Generator
from unittest import mock

import pytest

from opik.message_processing import messages
from opik.message_processing.replay import message_serialization, db_manager


@pytest.fixture
def manager() -> Generator[db_manager.DBManager, None, None]:
    """Fixture that creates a ReplayManager and ensures cleanup after test."""
    mgr = db_manager.DBManager(batch_size=10, batch_replay_delay=0.5)
    yield mgr
    mgr.close()


@pytest.fixture
def small_batch_manager() -> Generator[db_manager.DBManager, None, None]:
    """Fixture that creates a ReplayManager with a small batch size for testing batching."""
    mgr = db_manager.DBManager(batch_size=10, batch_replay_delay=0.1)
    yield mgr
    mgr.close()


def _create_trace_message(
    message_id: int, trace_id: str = "trace-1"
) -> messages.CreateTraceMessage:
    """Helper to create a CreateTraceMessage with required fields."""
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


def _create_span_message(
    message_id: int, span_id: str = "span-1"
) -> messages.CreateSpanMessage:
    """Helper to create a CreateSpanMessage with required fields."""
    msg = messages.CreateSpanMessage(
        span_id=span_id,
        trace_id="trace-1",
        project_name="test-project",
        parent_span_id=None,
        name="test-span",
        start_time=datetime.datetime(2024, 1, 1, 12, 0, 0),
        end_time=datetime.datetime(2024, 1, 1, 12, 0, 1),
        input={"prompt": "test"},
        output={"response": "result"},
        metadata=None,
        tags=None,
        type="general",
        usage=None,
        model=None,
        provider=None,
        error_info=None,
        total_cost=None,
        last_updated_at=None,
    )
    msg.message_id = message_id
    return msg


def _verify_message_was_inserted(
    message_id: int,
    message: messages.BaseMessage,
    conn: sqlite3.Connection,
    status: db_manager.MessageStatus = db_manager.MessageStatus.registered,
) -> None:
    """Verify a message row matches the provided message."""
    # Verify a message was inserted
    cursor = conn.execute(
        "SELECT message_id, status, message_type, message_json FROM messages WHERE message_id = ?",
        (message_id,),
    )
    row = cursor.fetchone()
    assert row is not None
    assert row[0] == message.message_id
    assert row[1] == status
    assert row[2] == message.message_type
    assert row[3] == message_serialization.serialize_message(message)


class TestReplayManagerInitialization:
    def test_init__default_parameters__creates_db_and_schema(
        self, manager: db_manager.DBManager
    ):
        """Test that ReplayManager initializes with default parameters."""
        assert manager.initialized
        assert not manager.closed
        assert not manager.failed
        assert manager.conn is not None

        # Verify schema was created
        cursor = manager.conn.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name='messages'"
        )
        assert cursor.fetchone() is not None

    def test_init__custom_connection__uses_provided_connection(self):
        """Test that ReplayManager uses a provided connection."""
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        mgr = db_manager.DBManager(conn=conn, batch_size=10, batch_replay_delay=0.1)

        try:
            assert mgr.conn is conn
            assert mgr.initialized
        finally:
            mgr.close()

    def test_init__connection_creation_failure__marks_as_error(self):
        """Test that schema creation failure marks the manager as an error."""
        # Create a read-only connection that will fail on CREATE TABLE
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        # Close the connection to make it unusable
        conn.close()

        mgr = db_manager.DBManager(conn=conn, batch_size=10, batch_replay_delay=0.1)

        assert mgr.failed
        assert not mgr.initialized


class TestReplayManagerClose:
    def test_close__normal_operation__closes_connection(
        self, manager: db_manager.DBManager
    ):
        """Test that close properly closes the connection."""
        assert not manager.closed

        manager.close()

        assert manager.closed

    def test_close__called_twice__no_error(self, manager: db_manager.DBManager):
        """Test that calling close twice doesn't raise an error."""
        manager.close()
        manager.close()  # Should not raise

        assert manager.closed


class TestRegisterMessage:
    def test_register_message__single_message__inserts_into_db(
        self, manager: db_manager.DBManager
    ):
        """Test registering a single message inserts it into the database."""
        message = _create_trace_message(message_id=1)

        manager.register_message(message)

        # Verify a message was inserted
        _verify_message_was_inserted(1, message, manager.conn)

    def test_register_message__single_message__upsert_into_db(
        self, manager: db_manager.DBManager
    ):
        """Test registering a single message inserts it into the database and upserts if exists."""
        message = _create_trace_message(message_id=1)

        manager.register_message(message, status=db_manager.MessageStatus.registered)

        # Verify a message was inserted
        _verify_message_was_inserted(1, message, manager.conn)

        # register the same message again, it should be updated
        manager.register_message(message, status=db_manager.MessageStatus.failed)

        # Verify a message was upserted
        cursor = manager.conn.execute(
            "SELECT status FROM messages WHERE message_id = ?", (1,)
        )
        row = cursor.fetchone()
        assert row[0] == db_manager.MessageStatus.failed

    def test_register_message__custom_status__uses_provided_status(
        self, manager: db_manager.DBManager
    ):
        """Test registering a message with a custom status."""
        message = _create_trace_message(message_id=1)

        manager.register_message(message, status=db_manager.MessageStatus.failed)

        cursor = manager.conn.execute(
            "SELECT status FROM messages WHERE message_id = ?", (1,)
        )
        row = cursor.fetchone()
        assert row[0] == db_manager.MessageStatus.failed

    def test_register_message__manager_failed__ignores_message(self):
        """Test that registering a message on an uninitialized manager is ignored."""
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        conn.execute(
            "CREATE TABLE messages (id INTEGER)"
        )  # Force failure on a register message operation
        mgr = db_manager.DBManager(conn=conn, batch_size=10, batch_replay_delay=0.1)

        try:
            message = _create_trace_message(message_id=1)
            with pytest.raises(sqlite3.OperationalError):
                mgr.register_message(message)  # the first operation should fail

            mgr.register_message(
                message
            )  # Should not raise as DBManager marked as failed
        finally:
            mgr.close()

    def test_register_message__manager_closed__ignores_message(
        self, manager: db_manager.DBManager
    ):
        """Test that registering a message on a closed manager is ignored."""
        manager.close()

        message = _create_trace_message(message_id=1)
        manager.register_message(message)  # Should not raise

    def test_register_message__message_id_none__raises_value_error(
        self, manager: db_manager.DBManager
    ):
        """Test that registering a message without message_id raises an error."""
        message = _create_trace_message(message_id=1)
        message.message_id = None

        with pytest.raises(ValueError, match="Message ID expected"):
            manager.register_message(message)


class TestRegisterMessages:
    def test_register_messages__batch_of_messages__inserts_all(
        self, manager: db_manager.DBManager
    ):
        """Test registering a batch of messages inserts all of them."""
        messages_list = [
            _create_trace_message(message_id=1, trace_id="trace-1"),
            _create_trace_message(message_id=2, trace_id="trace-2"),
            _create_trace_message(message_id=3, trace_id="trace-3"),
        ]

        manager.register_messages(messages_list)

        cursor = manager.conn.execute("SELECT COUNT(*) FROM messages")
        assert cursor.fetchone()[0] == 3

        for message in messages_list:
            _verify_message_was_inserted(message.message_id, message, manager.conn)

    def test_register_messages__batch_of_messages__upserts_all(
        self, manager: db_manager.DBManager
    ):
        """Test registering a batch of messages inserts all of them and  upserts if exists."""
        messages_list = [
            _create_trace_message(message_id=1, trace_id="trace-1"),
            _create_trace_message(message_id=2, trace_id="trace-2"),
            _create_trace_message(message_id=3, trace_id="trace-3"),
        ]

        manager.register_messages(messages_list)

        # register the same messages again, it should be updated
        manager.register_messages(messages_list, status=db_manager.MessageStatus.failed)

        cursor = manager.conn.execute("SELECT COUNT(*) FROM messages")
        assert cursor.fetchone()[0] == 3

        for message in messages_list:
            _verify_message_was_inserted(
                message.message_id,
                message,
                manager.conn,
                status=db_manager.MessageStatus.failed,
            )

    def test_register_messages__manager_closed__ignores_messages(
        self, manager: db_manager.DBManager
    ):
        """Test that registering messages on a closed manager is ignored."""
        manager.close()

        messages_list = [_create_trace_message(message_id=1)]
        manager.register_messages(messages_list)  # Should not raise


class TestUpdateMessage:
    def test_update_message__to_failed_status__updates_status(
        self, manager: db_manager.DBManager
    ):
        """Test updating a message status to failed."""
        message = _create_trace_message(message_id=1)
        manager.register_message(message)

        manager.update_message(1, db_manager.MessageStatus.failed)

        cursor = manager.conn.execute(
            "SELECT status FROM messages WHERE message_id = ?", (1,)
        )
        assert cursor.fetchone()[0] == db_manager.MessageStatus.failed

    def test_update_message__to_delivered_status__deletes_message(
        self, manager: db_manager.DBManager
    ):
        """Test updating a message status to delivered deletes the record."""
        message = _create_trace_message(message_id=1)
        manager.register_message(message)

        manager.update_message(1, db_manager.MessageStatus.delivered)

        cursor = manager.conn.execute(
            "SELECT COUNT(*) FROM messages WHERE message_id = ?", (1,)
        )
        assert cursor.fetchone()[0] == 0

    def test_update_message__manager_closed__ignores_update(
        self, manager: db_manager.DBManager
    ):
        """Test that an updating message on closed manager is ignored."""
        message = _create_trace_message(message_id=1)
        manager.register_message(message)
        manager.close()

        manager.update_message(1, db_manager.MessageStatus.failed)  # Should not raise


class TestUpdateMessagesBatch:
    def test_update_messages_batch__to_failed_status__updates_all(
        self, manager: db_manager.DBManager
    ):
        """Test updating a batch of messages to failed status."""
        messages_list = [
            _create_trace_message(message_id=i, trace_id=f"trace-{i}")
            for i in range(1, 4)
        ]
        manager.register_messages(messages_list)

        cursor = manager.conn.execute(
            "SELECT COUNT(*) FROM messages WHERE status = ?",
            (db_manager.MessageStatus.failed,),
        )
        assert cursor.fetchone()[0] == 0  # no messages updated yet

        manager.update_messages_batch([1, 2, 3], db_manager.MessageStatus.failed)

        cursor = manager.conn.execute(
            "SELECT COUNT(*) FROM messages WHERE status = ?",
            (db_manager.MessageStatus.failed,),
        )
        assert cursor.fetchone()[0] == 3  # all three messages updated to failed

    def test_update_messages_batch__to_delivered_status__deletes_all(
        self, manager: db_manager.DBManager
    ):
        """Test updating a batch of messages to delivered deletes them."""
        messages_list = [
            _create_trace_message(message_id=i, trace_id=f"trace-{i}")
            for i in range(1, 4)
        ]
        manager.register_messages(messages_list)

        manager.update_messages_batch([1, 2, 3], db_manager.MessageStatus.delivered)

        cursor = manager.conn.execute("SELECT COUNT(*) FROM messages")
        assert cursor.fetchone()[0] == 0


class TestGetMessage:
    def test_get_message__existing_message__returns_deserialized_message(
        self, manager: db_manager.DBManager
    ):
        """Test getting an existing message returns the deserialized BaseMessage."""
        original = _create_trace_message(message_id=1)
        manager.register_message(original)

        result = manager.get_message(message_id=1)

        assert result is not None
        assert isinstance(result, messages.CreateTraceMessage)
        assert result.trace_id == "trace-1"
        assert result.project_name == "test-project"

    def test_get_message__nonexistent_message__returns_none(
        self, manager: db_manager.DBManager
    ):
        """Test getting a nonexistent message returns None."""
        result = manager.get_message(message_id=999)

        assert result is None


class TestFetchFailedMessagesBatched:
    def test_fetch_failed_messages_batched__no_failed_messages__yields_nothing(
        self, manager: db_manager.DBManager
    ):
        """Test that no batches are yielded when there are no failed messages."""
        message = _create_trace_message(message_id=1)
        manager.register_message(message)  # Status is 'registered', not 'failed'

        batches = list(manager.fetch_failed_messages_batched(batch_size=10))

        assert len(batches) == 0

    def test_fetch_failed_messages_batched__fewer_than_batch_size__yields_single_batch(
        self, manager: db_manager.DBManager
    ):
        """Test that a single batch is yielded when messages < batch_size."""
        for i in range(5):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        batches = list(manager.fetch_failed_messages_batched(batch_size=10))

        assert len(batches) == 1
        assert len(batches[0]) == 5

    def test_fetch_failed_messages_batched__more_than_batch_size__yields_multiple_batches(
        self, manager: db_manager.DBManager
    ):
        """Test that multiple batches are yielded when messages > batch_size."""
        for i in range(25):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        batches = list(manager.fetch_failed_messages_batched(batch_size=10))

        assert len(batches) == 3
        assert len(batches[0]) == 10
        assert len(batches[1]) == 10
        assert len(batches[2]) == 5

    def test_fetch_failed_messages_batched__exact_batch_size__yields_correct_batches(
        self, manager: db_manager.DBManager
    ):
        """Test batching when message count is exact multiple of batch_size."""
        for i in range(20):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        batches = list(manager.fetch_failed_messages_batched(batch_size=10))

        assert len(batches) == 2
        assert len(batches[0]) == 10
        assert len(batches[1]) == 10

    def test_fetch_failed_messages_batched__reverse_insert_order__returns_ordered_by_id(
        self, manager: db_manager.DBManager
    ):
        """Test that batches return messages ordered by message_id."""
        # Insert in reverse order
        for i in range(10, 0, -1):
            msg = _create_trace_message(message_id=i, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        batches = list(manager.fetch_failed_messages_batched(batch_size=5))

        # Should be ordered by message_id ascending
        assert [m.id for m in batches[0]] == [1, 2, 3, 4, 5]
        assert [m.id for m in batches[1]] == [6, 7, 8, 9, 10]


class TestReplayFailedMessages:
    def test_replay_failed_messages__no_failed_messages__returns_zero(
        self, manager: db_manager.DBManager
    ):
        """Test that replay returns 0 when there are no failed messages."""
        callback = mock.Mock()

        result = manager.replay_failed_messages(callback)

        assert result == 0
        callback.assert_not_called()

    def test_replay_failed_messages__with_failed_messages__replays_all(
        self, manager: db_manager.DBManager
    ):
        """Test that all failed messages are replayed."""
        for i in range(3):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        callback = mock.Mock()
        result = manager.replay_failed_messages(callback)

        assert result == 3
        assert callback.call_count == 3

    def test_replay_failed_messages__after_replay__status_updated_to_registered(
        self, manager: db_manager.DBManager
    ):
        """Test that replayed messages have their status updated to registered."""
        message_id = 1
        msg = _create_trace_message(message_id=message_id)
        manager.register_message(msg, status=db_manager.MessageStatus.failed)

        callback = mock.Mock()
        manager.replay_failed_messages(callback)

        # Check status was updated to registered
        cursor = manager.conn.execute(
            "SELECT status FROM messages WHERE message_id = ?", (message_id,)
        )
        assert cursor.fetchone()[0] == db_manager.MessageStatus.registered

    def test_replay_failed_messages__callback_invocation__receives_deserialized_messages(
        self, manager: db_manager.DBManager
    ):
        """Test that callback receives properly deserialized BaseMessage objects."""
        original = _create_trace_message(message_id=1)
        manager.register_message(original, status=db_manager.MessageStatus.failed)

        received_messages = []

        def callback(msg: messages.BaseMessage) -> None:
            received_messages.append(msg)

        manager.replay_failed_messages(callback)

        assert len(received_messages) == 1
        assert isinstance(received_messages[0], messages.CreateTraceMessage)
        assert received_messages[0].trace_id == "trace-1"

    def test_replay_failed_messages__manager_closed__returns_zero(
        self, manager: db_manager.DBManager
    ):
        """Test that replay returns 0 when the manager is closed."""
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg, status=db_manager.MessageStatus.failed)
        manager.close()

        callback = mock.Mock()
        result = manager.replay_failed_messages(callback)

        assert result == 0
        callback.assert_not_called()

    def test_replay_failed_messages__large_batch__processes_all_batches(
        self, small_batch_manager: db_manager.DBManager
    ):
        """Test that batched processing handles all messages across batches."""
        count = 150
        for i in range(count):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            small_batch_manager.register_message(
                msg, status=db_manager.MessageStatus.failed
            )

        callback = mock.Mock()
        small_batch_manager.batch_replay_delay = (
            0.01  # Short delay to process all batches faster for testing
        )
        result = small_batch_manager.replay_failed_messages(callback)

        assert result == count
        assert callback.call_count == count

    def test_replay_failed_messages__callback_raises_exception__continues_processing(
        self, manager: db_manager.DBManager
    ):
        """Test that exceptions in callback don't stop processing other messages."""
        for i in range(3):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        call_count = 0

        def callback(msg: messages.BaseMessage) -> None:
            nonlocal call_count
            call_count += 1
            if call_count == 2:
                raise ValueError("Test error")

        result = manager.replay_failed_messages(callback)

        # Should return 2 because one callback failed
        assert result == 2
        assert call_count == 3


class TestReplayManagerStatusProperties:
    def test_closed__not_closed__returns_false(self, manager: db_manager.DBManager):
        """Test closed property returns False when not closed."""
        assert not manager.closed

    def test_closed__after_close__returns_true(self, manager: db_manager.DBManager):
        """Test closed property returns True after close."""
        manager.close()
        assert manager.closed

    def test_initialized__after_init__returns_true(self, manager: db_manager.DBManager):
        """Test initialized property returns True after successful init."""
        assert manager.initialized

    def test_failed__normal_operation__returns_false(
        self, manager: db_manager.DBManager
    ):
        """Test failed property returns False during normal operation."""
        assert not manager.failed

    def test_failed__after_db_error__returns_true(self):
        """Test failed property returns True after a DB error."""
        # Use a closed connection to force init failure
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        conn.close()
        mgr = db_manager.DBManager(conn=conn, batch_size=10, batch_replay_delay=0.1)

        assert mgr.failed


class TestFailedMessagesCount:
    def test_failed_messages_count__no_failed_messages__returns_zero(
        self, manager: db_manager.DBManager
    ):
        """Test that count returns 0 when there are no failed messages."""
        message = _create_trace_message(message_id=1)
        manager.register_message(message)  # Status is 'registered', not 'failed'

        result = manager.failed_messages_count()

        assert result == 0

    def test_failed_messages_count__with_failed_messages__returns_correct_count(
        self, manager: db_manager.DBManager
    ):
        """Test that count returns the correct number of failed messages."""
        for i in range(5):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        result = manager.failed_messages_count()

        assert result == 5

    def test_failed_messages_count__mixed_statuses__counts_only_failed(
        self, manager: db_manager.DBManager
    ):
        """Test that only messages with 'failed' status are counted."""
        # Register some as 'registered' and some as 'failed'
        for i in range(3):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg)  # registered status

        for i in range(3, 5):
            msg = _create_trace_message(message_id=i + 1, trace_id=f"trace-{i}")
            manager.register_message(msg, status=db_manager.MessageStatus.failed)

        result = manager.failed_messages_count()

        assert result == 2

    def test_failed_messages_count__manager_not_initialized__returns_negative_one(self):
        """Test that count returns -1 when the manager is not initialized."""
        conn = sqlite3.connect(":memory:", check_same_thread=False)
        conn.close()
        mgr = db_manager.DBManager(conn=conn, batch_size=10, batch_replay_delay=0.1)

        result = mgr.failed_messages_count()

        assert result == -1

    def test_failed_messages_count__manager_closed__returns_negative_one(
        self, manager: db_manager.DBManager
    ):
        """Test that count returns -1 when the manager is closed."""
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg, status=db_manager.MessageStatus.failed)
        manager.close()

        result = manager.failed_messages_count()

        assert result == -1

    def test_failed_messages_count__db_error__returns_negative_one_and_marks_failed(
        self, manager: db_manager.DBManager
    ):
        """Test that a DB error returns -1 and marks the manager as failed."""
        msg = _create_trace_message(message_id=1)
        manager.register_message(msg, status=db_manager.MessageStatus.failed)

        # Force a DB error by dropping the messages table
        manager.conn.execute("DROP TABLE messages")

        result = manager.failed_messages_count()

        assert result == -1
        assert manager.failed


class TestDbMessageToMessage:
    def test_db_message_to_message__supported_type__returns_correct_message(
        self, manager: db_manager.DBManager
    ):
        """Test conversion of DBMessage to BaseMessage for supported types."""
        original = _create_trace_message(message_id=1)
        manager.register_message(original)

        db_message = manager.get_db_message(1)
        result = db_manager.db_message_to_message(db_message)

        assert isinstance(result, messages.CreateTraceMessage)
        assert result.trace_id == "trace-1"

    def test_db_message_to_message__unsupported_type__raises_value_error(self):
        """Test that an unsupported message type raises ValueError."""
        db_message = db_manager.DBMessage(
            id=1,
            type="UnsupportedMessageType",
            json="{}",
            status=db_manager.MessageStatus.registered,
        )

        with pytest.raises(ValueError, match="Unsupported message type"):
            db_manager.db_message_to_message(db_message)
