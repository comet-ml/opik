"""On-disk SQLite message store used by the replay flow to persist messages
that failed to reach the Opik server. DBManager exposes helpers for the full
message lifecycle: register_message(s), fetch_failed_messages_batched, and
replay_failed_messages (which accepts a ReplayCallback, typically Streamer.put,
to re-inject messages). The manager tracks three states — undefined, initialized,
closed, and error — and if the underlying database becomes unavailable, it marks
itself as failed (error) and logs that resiliency features are disabled.
"""

import logging
import os
import shutil
import sqlite3
import tempfile
import threading
import time
from enum import unique, IntEnum
from typing import List, NamedTuple, Optional, Iterator

from opik.message_processing import messages
from opik.message_processing.replay import message_serialization

from .types import ReplayCallback


DEFAULT_DB_FILE = "opik_messages.db"

LOGGER = logging.getLogger(__name__)


@unique
class MessageStatus(IntEnum):
    """
    Represents the status of a message.

    Attributes:
        registered: Message queued for delivery.
        delivered: Message delivered successfully.
        failed: Delivery failed.
        replaying: Message is currently being replayed by the leader.
    """

    registered = 1
    delivered = 2
    failed = 3
    replaying = 4


def _message_status_from_int(value: int) -> MessageStatus:
    """Return the MessageStatus for an integer value.

    If the value is unknown (e.g. written by a newer/older SDK version), fall
    back to ``failed`` so the message remains eligible for replay rather than
    crashing the reader.
    """
    try:
        return MessageStatus(value)
    except ValueError:
        LOGGER.warning("Unknown message status value %r, treating as failed", value)
        return MessageStatus.failed


@unique
class DBManagerStatus(IntEnum):
    """Lifecycle states for DBManager."""

    undefined = 1
    initialized = 2
    closed = 3
    error = 4


class DBMessage(NamedTuple):
    """Database row representation of a stored message."""

    id: int
    type: str
    json: str
    status: MessageStatus


def _serialize_message(message: messages.BaseMessage) -> str:
    return message_serialization.serialize_message(message)


class DBManager:
    """
    Thread-safe SQLite store for replay messages.

    When ``db_file`` is provided, it is used as a single shared SQLite file.
    Multiple processes can write concurrently because the connection runs in
    WAL (write-ahead logging) mode.

    When ``db_file`` is ``None``, a temporary directory and file are created and
    cleaned up on close.
    """

    def __init__(
        self,
        batch_size: int,
        batch_replay_delay: float,
        db_file: Optional[str] = None,
        conn: Optional[sqlite3.Connection] = None,
        sync_lock: Optional[threading.RLock] = None,
    ) -> None:
        self.batch_size = batch_size
        self.batch_replay_delay = batch_replay_delay
        self.status = DBManagerStatus.undefined
        self.tmp_dir: Optional[str] = None

        if db_file is None:
            # Default mode: temporary file + automatic cleanup.
            self.tmp_dir = tempfile.mkdtemp()
            db_file = os.path.join(self.tmp_dir, DEFAULT_DB_FILE)
        else:
            # Single shared file mode: ensure the parent directory exists.
            parent_dir = os.path.dirname(os.path.abspath(db_file))
            if parent_dir:
                os.makedirs(parent_dir, exist_ok=True)

        self.db_file = db_file

        if conn is None:
            conn = sqlite3.connect(self.db_file, check_same_thread=False)
        self.conn = conn

        if sync_lock is None:
            self.__lock__ = threading.RLock()
        else:
            self.__lock__ = sync_lock

        # Non-reentrant mutex that serializes concurrent replay_failed_messages
        # calls. Producers use self.__lock__, so they are never blocked by replay.
        self._replay_mutex = threading.Lock()

        self._create_db_schema()

    def _configure_wal(self) -> None:
        """Enable WAL mode so multiple processes can write concurrently."""
        with self.conn:
            self.conn.execute("PRAGMA journal_mode=WAL")
            self.conn.execute("PRAGMA busy_timeout=5000")

    def _create_db_schema(self) -> None:
        try:
            self._configure_wal()
            with self.__lock__:
                with self.conn:
                    self.conn.execute(
                        """CREATE TABLE IF NOT EXISTS messages
                            (message_id INTEGER PRIMARY KEY AUTOINCREMENT,
                             status INTEGER NOT NULL,
                             message_type TEXT NOT NULL,
                             message_json TEXT NOT NULL)"""
                    )
                    self.status = DBManagerStatus.initialized
        except Exception as ex:
            msg = f"Database schema creation failed, reason: {ex}"
            self._mark_as_db_failed(msg)
            LOGGER.warning(msg, exc_info=True)

    def close(self) -> None:
        """Release all associated resources."""
        if self.closed:
            return

        with self.__lock__:
            self.status = DBManagerStatus.closed

            try:
                LOGGER.debug("Closing messages DB connection")
                self.conn.close()
            except Exception as e:
                LOGGER.debug(
                    "Failed to close messages DB connection: %s", e, exc_info=True
                )

            # Delete temporary data.
            if self.tmp_dir is not None:
                try:
                    LOGGER.debug("Cleaning temporary data dir: %r", self.tmp_dir)
                    shutil.rmtree(self.tmp_dir)
                except Exception as e:
                    LOGGER.debug(
                        "Failed to clean temporary data dir: %r, reason: %s",
                        self.tmp_dir,
                        e,
                        exc_info=True,
                    )

    def register_message(
        self,
        message: messages.BaseMessage,
        status: MessageStatus = MessageStatus.registered,
    ) -> None:
        """
        Persist a message. If ``message.message_id`` is ``None``, SQLite assigns
        an auto-increment primary key and updates ``message.message_id`` with it.
        """
        if not self.initialized:
            LOGGER.debug("Not initialized - register message ignored")
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - register message ignored")
                return

            message_json = _serialize_message(message)

            try:
                with self.conn:
                    if message.message_id is None:
                        cursor = self.conn.execute(
                            "INSERT INTO messages(status, message_type, message_json) VALUES (?, ?, ?)",
                            (status, message.message_type, message_json),
                        )
                        message.message_id = cursor.lastrowid
                    else:
                        self.conn.execute(
                            "INSERT INTO messages(message_id, status, message_type, message_json) VALUES (?, ?, ?, ?)"
                            " ON CONFLICT(message_id) DO UPDATE SET status = excluded.status,"
                            " message_type = excluded.message_type, message_json = excluded.message_json",
                            (message.message_id, status, message.message_type, message_json),
                        )
            except Exception as ex:
                self._mark_as_db_failed(
                    f"register_message: failed to insert message into DB, reason: {ex}"
                )
                raise

    def register_messages(
        self,
        messages_batch: List[messages.BaseMessage],
        status: MessageStatus = MessageStatus.registered,
    ) -> None:
        """Persist a batch of messages under a single lock."""
        if not self.initialized:
            LOGGER.debug("Not initialized - register messages list ignored")
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - register messages list ignored")
                return

            try:
                with self.conn:
                    for message in messages_batch:
                        message_json = _serialize_message(message)
                        if message.message_id is None:
                            cursor = self.conn.execute(
                                "INSERT INTO messages(status, message_type, message_json) VALUES (?, ?, ?)",
                                (status, message.message_type, message_json),
                            )
                            message.message_id = cursor.lastrowid
                        else:
                            self.conn.execute(
                                "INSERT INTO messages(message_id, status, message_type, message_json) VALUES (?, ?, ?, ?)"
                                " ON CONFLICT(message_id) DO UPDATE SET status = excluded.status,"
                                " message_type = excluded.message_type, message_json = excluded.message_json",
                                (message.message_id, status, message.message_type, message_json),
                            )
            except Exception as ex:
                self._mark_as_db_failed(
                    f"register_messages: failed to insert messages into DB, reason: {ex}"
                )
                raise

    def update_messages_batch(
        self, message_ids: List[int], status: MessageStatus
    ) -> None:
        """Update the status of a batch of messages, deleting delivered rows."""
        if not self.initialized:
            LOGGER.debug(
                "Not initialized - messages batch update ignored, size: %d, status: %r",
                len(message_ids),
                status,
            )
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning(
                    "Already closed - messages batch update ignored, size: %d, status: %r",
                    len(message_ids),
                    status,
                )
                return

            try:
                with self.conn:
                    if status == MessageStatus.delivered:
                        db_ids = [(message_id,) for message_id in message_ids]
                        c = self.conn.executemany(
                            "DELETE FROM messages WHERE message_id = ?",
                            db_ids,
                        )
                        LOGGER.debug(
                            "Deleted %d DB message records for %d delivered messages",
                            c.rowcount,
                            len(message_ids),
                        )
                    else:
                        db_status_ids = [
                            (status, message_id) for message_id in message_ids
                        ]
                        c = self.conn.executemany(
                            "UPDATE messages SET status = ? WHERE message_id = ?",
                            db_status_ids,
                        )
                        LOGGER.debug(
                            "Updated %d DB message records for %d messages to have status: %r",
                            c.rowcount,
                            len(message_ids),
                            status,
                        )
            except Exception as ex:
                self._mark_as_db_failed(
                    f"update_messages_batch: failed to update messages batch in the DB, reason: {ex}"
                )
                raise

    def update_message(self, message_id: int, status: MessageStatus) -> None:
        """Update the status of a single message or delete it if delivered."""
        if not self.initialized:
            LOGGER.debug(
                "Not initialized - message update ignored, id: %d, status: %r",
                message_id,
                status,
            )
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning(
                    "Already closed - message update ignored, id: %d, status: %r",
                    message_id,
                    status,
                )
                return
            try:
                with self.conn:
                    if status == MessageStatus.delivered:
                        self.conn.execute(
                            "DELETE FROM messages WHERE message_id = ?", (message_id,)
                        )
                    else:
                        self.conn.execute(
                            "UPDATE messages SET status = ? WHERE message_id = ?",
                            (status, message_id),
                        )
            except Exception as ex:
                self._mark_as_db_failed(
                    f"update_message: failed to update message in the DB, reason: {ex}"
                )
                raise

    def replay_failed_messages(self, replay_callback: ReplayCallback) -> int:
        """
        Replays previously failed messages in batches.

        Concurrent calls are serialized by a non-blocking mutex so the same failed
        messages are not fetched twice.
        """
        if self.closed:
            LOGGER.debug("DBManager already closed")
            return 0

        if not self.initialized:
            LOGGER.debug("Not initialized - messages replay ignored")
            return 0

        if not self._replay_mutex.acquire(blocking=False):
            LOGGER.debug("Replay already in progress - skipping concurrent call")
            return 0

        try:
            return self._replay_failed_messages_impl(replay_callback)
        finally:
            self._replay_mutex.release()

    def _replay_failed_messages_impl(self, replay_callback: ReplayCallback) -> int:
        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - messages replay ignored")
                return 0

        total_replayed = 0
        for db_messages in self.fetch_failed_messages_batched(self.batch_size):
            # Replay first, then mark the accepted messages as replaying. This
            # keeps rows in the FAILED state if the callback cannot accept the
            # message (e.g. the streamer is draining or preprocessing failed).
            accepted_ids = self._replay_messages(
                db_messages=db_messages, replay_callback=replay_callback
            )

            if accepted_ids:
                with self.__lock__:
                    if self.closed:
                        LOGGER.debug(
                            "Closed during replay - stopping after %d replayed",
                            total_replayed,
                        )
                        break
                    try:
                        with self.conn:
                            params = [
                                (int(MessageStatus.replaying), message_id)
                                for message_id in accepted_ids
                            ]
                            c = self.conn.executemany(
                                "UPDATE messages SET status = ? WHERE message_id = ?",
                                params,
                            )
                            LOGGER.debug(
                                "Marked %d DB message records as replaying",
                                c.rowcount,
                            )
                    except Exception as ex:
                        self._mark_as_db_failed(
                            f"replay_failed_messages: failed to mark replaying messages in the DB, reason: {ex}"
                        )
                        raise

            total_replayed += len(accepted_ids)

            # Pause between full batches without holding the lock.
            if len(accepted_ids) >= self.batch_size:
                time.sleep(self.batch_replay_delay)

        return total_replayed

    def _replay_messages(
        self, db_messages: List[DBMessage], replay_callback: ReplayCallback
    ) -> List[int]:
        """Invoke the replay callback for each failed message.

        Returns the list of message ids that the callback accepted. Rows stay
        FAILED if the callback declines the message or raises.
        """
        LOGGER.debug("Replaying %d failed messages to streamer", len(db_messages))
        accepted_ids: List[int] = []
        for message in db_messages:
            try:
                base_message = db_message_to_message(message)
                accepted = replay_callback(base_message)
                if accepted is False:
                    LOGGER.warning(
                        "Replay callback declined message with id=%r, type=%r, leaving as failed",
                        message.id,
                        message.type,
                    )
                    continue
                accepted_ids.append(message.id)
            except Exception as e:
                LOGGER.error(
                    "Failed to replay message with id=%r, type=%r, status=%r, reason: %s",
                    message.id,
                    message.type,
                    message.status,
                    e,
                    exc_info=True,
                )

        return accepted_ids

    def get_message(self, message_id: int) -> Optional[messages.BaseMessage]:
        """Fetch a single message by id."""
        db_message = self.get_db_message(message_id)
        if db_message is not None:
            return db_message_to_message(db_message)
        return None

    def get_db_message(self, message_id: int) -> Optional[DBMessage]:
        """Fetch a single database row by id."""
        with self.__lock__:
            if self.closed:
                return None
            try:
                with self.conn:
                    c = self.conn.execute(
                        "SELECT message_id, message_type, message_json, status FROM messages WHERE message_id = ?",
                        (message_id,),
                    )
                    row = c.fetchone()
                    if row is not None:
                        return DBMessage(
                            id=row[0],
                            type=row[1],
                            json=row[2],
                            status=_message_status_from_int(row[3]),
                        )
                    return None
            except Exception as ex:
                self._mark_as_db_failed(
                    f"get_db_message: failed to query DB, reason: {ex}"
                )
                raise

    @property
    def closed(self) -> bool:
        return self.status == DBManagerStatus.closed

    @property
    def initialized(self) -> bool:
        return self.status == DBManagerStatus.initialized

    @property
    def failed(self) -> bool:
        return self.status == DBManagerStatus.error

    def fetch_failed_messages_batched(
        self, batch_size: int
    ) -> Iterator[List[DBMessage]]:
        """Yield failed messages in bounded batches ordered by message_id."""
        last_seen_id = -1
        while True:
            batch: List[DBMessage] = []
            with self.__lock__:
                try:
                    rows = self.conn.execute(
                        "SELECT message_id, message_type, message_json FROM messages "
                        "WHERE status = ? AND message_id > ? ORDER BY message_id LIMIT ?",
                        (MessageStatus.failed, last_seen_id, batch_size),
                    )
                except Exception as ex:
                    self._mark_as_db_failed(
                        f"fetch_failed_messages_batched: failed to fetch failed messages from the DB, reason: {ex}"
                    )
                    raise

                for row in rows:
                    batch.append(
                        DBMessage(
                            id=row[0],
                            type=row[1],
                            json=row[2],
                            status=MessageStatus.failed,
                        )
                    )

            if not batch:
                break

            last_seen_id = batch[-1].id
            yield batch

    def failed_messages_count(self) -> int:
        """Return the number of failed messages or -1 if unavailable."""
        if not self.initialized:
            LOGGER.debug("Not initialized - failed messages count ignored")
            return -1

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - failed messages count ignored")
                return -1

            try:
                return self.conn.execute(
                    "SELECT COUNT(*) FROM messages WHERE status = ?",
                    (MessageStatus.failed,),
                ).fetchone()[0]
            except Exception as ex:
                msg = f"failed_messages_count: failed to get failed messages count, reason: {ex}"
                LOGGER.error(msg)
                self._mark_as_db_failed(msg)
                return -1

    def reset_replaying_messages(self) -> int:
        """Reset messages left in the replaying state back to failed.

        Called when a new replay leader acquires the lock. Messages that were
        marked ``replaying`` by a previous leader may not have been delivered if
        that leader died, so they must be eligible for replay again.
        """
        if not self.initialized:
            LOGGER.debug("Not initialized - reset replaying messages ignored")
            return 0

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - reset replaying messages ignored")
                return 0

            try:
                with self.conn:
                    c = self.conn.execute(
                        "UPDATE messages SET status = ? WHERE status = ?",
                        (MessageStatus.failed, MessageStatus.replaying),
                    )
                    LOGGER.debug(
                        "Reset %d replaying message(s) back to failed", c.rowcount
                    )
                    return c.rowcount
            except Exception as ex:
                # Bootstrap errors during leader election should not crash the
                # application. Mark the DB as failed (which disables offline replay
                # resiliency) and swallow the error so the caller can continue.
                msg = f"reset_replaying_messages: failed to reset replaying messages, reason: {ex}"
                LOGGER.error(msg)
                self._mark_as_db_failed(msg)
                return 0

    def _mark_as_db_failed(self, message: str) -> None:
        self.status = DBManagerStatus.error
        LOGGER.error(
            "Due to an internal error, some network resiliency features were disabled "
            "which could lead to data loss. Contact us at support@comet.com. Error details: %r",
            message,
        )

    def get_max_message_id(self) -> int:
        """Return the maximum message_id or -1 if empty/unavailable."""
        if not self.initialized:
            LOGGER.debug("Not initialized - get_max_message_id ignored")
            return -1

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - get_max_message_id ignored")
                return -1

            try:
                with self.conn:
                    row = self.conn.execute(
                        "SELECT COALESCE(MAX(message_id), -1) FROM messages"
                    ).fetchone()
                    return row[0]
            except Exception as ex:
                msg = f"get_max_message_id: failed to query DB, reason: {ex}"
                LOGGER.error(msg, exc_info=True)
                self._mark_as_db_failed(msg)
                return -1


SUPPORTED_MESSAGE_TYPES = {
    messages.CreateTraceMessage.message_type: messages.CreateTraceMessage,
    messages.UpdateTraceMessage.message_type: messages.UpdateTraceMessage,
    messages.CreateSpanMessage.message_type: messages.CreateSpanMessage,
    messages.UpdateSpanMessage.message_type: messages.UpdateSpanMessage,
    messages.AddTraceFeedbackScoresBatchMessage.message_type: messages.AddTraceFeedbackScoresBatchMessage,
    messages.AddSpanFeedbackScoresBatchMessage.message_type: messages.AddSpanFeedbackScoresBatchMessage,
    messages.AddThreadsFeedbackScoresBatchMessage.message_type: messages.AddThreadsFeedbackScoresBatchMessage,
    messages.CreateSpansBatchMessage.message_type: messages.CreateSpansBatchMessage,
    messages.CreateTraceBatchMessage.message_type: messages.CreateTraceBatchMessage,
    messages.GuardrailBatchMessage.message_type: messages.GuardrailBatchMessage,
    messages.CreateExperimentItemsBatchMessage.message_type: messages.CreateExperimentItemsBatchMessage,
    messages.AddAssertionResultsBatchMessage.message_type: messages.AddAssertionResultsBatchMessage,
    messages.CreateAttachmentMessage.message_type: messages.CreateAttachmentMessage,
}


def db_message_to_message(db_message: DBMessage) -> messages.BaseMessage:
    """Deserialize a DB row into a message object."""
    message_class = SUPPORTED_MESSAGE_TYPES.get(db_message.type)
    if message_class is None:
        raise ValueError(f"Unsupported message type: {db_message.type}")

    message = message_serialization.deserialize_message(
        message_class, json_str=db_message.json
    )
    # The DB row ID is the authoritative message_id. It is intentionally not
    # serialized into message_json so the same message object can be stored
    # under different IDs; restore it here so replay can update/delete the
    # correct row.
    message.message_id = db_message.id
    return message
