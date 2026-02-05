import logging
import os
import shutil
import sqlite3
import tempfile
import threading
from enum import unique, IntEnum
from typing import List, NamedTuple, Callable, Optional, Dict, Iterator

from opik.message_processing import messages
from opik.message_processing.replay import message_serialization

DEFAULT_DB_FILE = "opik_messages.db"
DEFAULT_BATCH_SIZE = 1000

LOGGER = logging.getLogger(__name__)


@unique
class MessageStatus(IntEnum):
    """
    Represents the status of a message.

    This enumeration defines the potential statuses that a message can
    have in a messaging system. Used for categorizing messages based on
    their lifecycle stage or outcome.

    Attributes:
        registered: Represents a message that has been successfully
            registered but not yet processed or delivered.
        delivered : Represents a message that has been successfully
            delivered to its recipient.
        failed: Represents a message for which delivery has failed.
    """

    registered = 1
    delivered = 2
    failed = 3


@unique
class ManagerStatus(IntEnum):
    """Represents various status values for a manager.

    Defines a set of distinct states that a manager can occupy during its lifecycle.
    This can be used to track and manage the status of a manager in an application.
    """

    undefined = 1
    initialized = 2
    closed = 3
    error = 4


class DBMessage(NamedTuple):
    """
    Represents a database message entity.

    This class is used to encapsulate information about messages stored in
    or retrieved from a database. It provides a structured format for handling
    message data, including its unique identifier, type, JSON content, and
    current status.

    Attributes:
        id: The unique identifier of the message.
        type: The type/category of the message.
        json: The JSON-encoded content of the message.
        status: The current status of the message.
    """

    id: int
    type: str
    json: str
    status: MessageStatus


ReplayCallback = Callable[[messages.BaseMessage], None]


class ReplayManager:
    """
    Manages message storage, batch processing, and database operations within a replay
    system.

    This class provides functionalities to handle message registrations, batch updates,
    and database schema management. It ensures thread-safe operations, cleans up
    temporary files, and handles database connections as part of its lifecycle.

    """

    def __init__(
        self,
        batch_size: int = DEFAULT_BATCH_SIZE,
        db_file: Optional[str] = None,
        conn: Optional[sqlite3.Connection] = None,
    ) -> None:
        """
        Initializes the Manager class, setting up the database connection, temporary
        directory, and other necessary properties for managing batch operations.

        Args:
            batch_size: The size of batches for processing. Defaults to
                DEFAULT_BATCH_SIZE.
            db_file: Path to the database file. If not provided, a
                temporary file will be created in a temporary directory.
            conn: A pre-existing database connection.
                If not provided, a new connection is created.
        """
        self.batch_size = batch_size
        self.status = ManagerStatus.undefined
        self.tmp_dir = tempfile.mkdtemp()
        if db_file is None:
            db_file = os.path.join(self.tmp_dir, DEFAULT_DB_FILE)

        self.db_file = db_file
        # open DB connection if appropriate
        if conn is None:
            conn = sqlite3.connect(self.db_file, check_same_thread=False)
        self.conn = conn

        self.__lock__ = threading.RLock()
        self._create_db_schema()

        self.message_files: Dict[int, str] = {}

    def _create_db_schema(self) -> None:
        try:
            with self.__lock__:
                with self.conn:
                    self.conn.execute(
                        """CREATE TABLE messages IF NOT EXISTS
                                            (message_id INTEGER NOT NULL PRIMARY KEY,
                                            status INTEGER NOT NULL,
                                            message_type TEXT NOT NULL,
                                            message_json TEXT NOT NULL)"""
                    )
                    self.status = ManagerStatus.initialized
        except Exception as ex:
            msg = f"Database schema creation failed, reason: {ex}"
            self._mark_as_db_failed(msg)
            LOGGER.debug(msg, exc_info=True)

    def close(self) -> None:
        if self.closed:
            return

        with self.__lock__:
            self.status = ManagerStatus.closed

            try:
                LOGGER.debug("Closing messages DB connection")
                self.conn.close()
            except Exception as e:
                LOGGER.debug(
                    "Failed to close messages DB connection: %s", e, exc_info=True
                )

            # delete temporary data
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
        if not self.initialized:
            LOGGER.debug("Not initialized - register message ignored")
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - register message ignored")
                return

            message_json = self._preprocess_registered_message(message)
            # insert into DB
            values = (
                message.message_id,
                status,
                message.message_type,
                message_json,
            )
            try:
                with self.conn:
                    self.conn.execute("INSERT INTO messages VALUES (?,?,?,?)", values)
            except Exception as ex:
                msg = (
                    "register_message: failed to insert message into DB, reason: %r"
                    % ex
                )
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)

    def register_messages(
        self,
        messages_batch: List[messages.BaseMessage],
        status: MessageStatus = MessageStatus.registered,
    ) -> None:
        if not self.initialized:
            LOGGER.debug("Not initialized - register messages list ignored")
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - register messages list ignored")
                return

            values = []
            for message in messages_batch:
                message_json = self._preprocess_registered_message(message)
                values.append(
                    (
                        message.message_id,
                        status,
                        message.message_type,
                        message_json,
                    )
                )

            try:
                with self.conn:
                    self.conn.executemany(
                        "INSERT INTO messages VALUES (?,?,?,?)", values
                    )
            except Exception as ex:
                msg = f"register_messages: failed to insert messages into DB, reason: {ex}"
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)

    def _clean_message_leftovers(self, message_id: int) -> None:
        # Cleanup message file
        if message_id in self.message_files:
            try:
                os.remove(self.message_files[message_id])
            except Exception as e:
                LOGGER.debug(
                    "Failed to remove temporary file: %r of the message: %d, reason: %s",
                    self.message_files[message_id],
                    message_id,
                    e,
                    exc_info=True,
                )

    def _preprocess_registered_message(self, message: messages.BaseMessage) -> str:
        if message.message_id is None:
            raise ValueError("Message ID expected")

        if isinstance(message, messages.CreateAttachmentMessage):
            self.message_files[message.message_id] = message.file_path

        return message_serialization.serialize_message(message)

    def update_messages_batch(
        self, message_ids: List[int], status: MessageStatus
    ) -> None:
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
                        # delete saved message callbacks and leftovers
                        for message_id in message_ids:
                            self._clean_message_leftovers(message_id)
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
                msg = f"update_messages_batch: failed to update messages batch in the DB, reason: {ex}"
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)

    def update_message(self, message_id: int, status: MessageStatus) -> None:
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
                        # remove delivered messages
                        self.conn.execute(
                            "DELETE FROM messages WHERE message_id = ?", (message_id,)
                        )
                        # delete saved message callbacks and leftovers
                        self._clean_message_leftovers(message_id)
                    else:
                        self.conn.execute(
                            "UPDATE messages SET status = ? WHERE message_id = ?",
                            (status, message_id),
                        )
            except Exception as ex:
                msg = (
                    f"update_message: failed to update message in the DB, reason: {ex}"
                )
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)

    def replay_failed_messages(self, replay_callback: ReplayCallback) -> int:
        if not self.initialized:
            LOGGER.debug("Not initialized - messages replay ignored")
            return 0

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - messages replay ignored")
                return 0

            total_replayed = 0
            for db_messages in self._fetch_failed_messages_batched(self.batch_size):
                if self.closed:
                    break

                params = [
                    (int(MessageStatus.registered), message.id)
                    for message in db_messages
                ]
                # update DB records to mark failed messages as in progress
                try:
                    with self.conn:
                        c = self.conn.executemany(
                            "UPDATE messages SET status = ? WHERE message_id = ?",
                            params,
                        )
                        LOGGER.debug(
                            "Updated %d DB message records for %d failed messages",
                            c.rowcount,
                            len(db_messages),
                        )
                except Exception as ex:
                    msg = f"replay_failed_messages: failed to update messages in the DB, reason: {ex}"
                    self._mark_as_db_failed(msg)
                    LOGGER.debug(msg, exc_info=True)
                    return total_replayed

                total_replayed += self._replay_messages(
                    db_messages=db_messages, replay_callback=replay_callback
                )

            return total_replayed

    def _replay_messages(
        self, db_messages: List[DBMessage], replay_callback: ReplayCallback
    ) -> int:
        LOGGER.debug("Replaying %d failed messages to streamer", len(db_messages))
        for message in db_messages:
            if self.closed:
                return 0

            try:
                base_message = db_message_to_message(message)
                replay_callback(base_message)
            except Exception as e:
                LOGGER.error(
                    "Failed to replay message with id=%r, type=%r, status=%r, reason: %s",
                    message.id,
                    message.type,
                    message.status,
                    e,
                    exc_info=True,
                )

        return len(db_messages)

    def get_message(self, message_id: int) -> Optional[messages.BaseMessage]:
        db_message = self.get_db_message(message_id)
        if db_message is not None:
            return db_message_to_message(db_message)
        else:
            return None

    def get_db_message(self, message_id: int) -> Optional[DBMessage]:
        with self.conn:
            c = self.conn.execute(
                "SELECT message_id, message_type, message_json, status FROM messages WHERE message_id = ?",
                (message_id,),
            )
            row = c.fetchone()
            if row is not None:
                return DBMessage(id=row[0], type=row[1], json=row[2], status=row[3])
            else:
                return None

    @property
    def closed(self) -> bool:
        return self.status == ManagerStatus.closed

    @property
    def initialized(self) -> bool:
        return self.status == ManagerStatus.initialized

    @property
    def failed(self) -> bool:
        return self.status == ManagerStatus.error

    def _fetch_failed_messages_batched(
        self, batch_size: int
    ) -> Iterator[List[DBMessage]]:
        """Fetch failed messages from DB in bounded batches to avoid OOM.

        Uses cursor-based pagination with message_id for efficient iteration.
        Yields batches of DBMessage objects until no more failed messages remain.
        """
        last_seen_id = -1
        while True:
            batch: List[DBMessage] = []
            rows = self.conn.execute(
                "SELECT message_id, message_type, message_json FROM messages "
                "WHERE status = ? AND message_id > ? ORDER BY message_id LIMIT ?",
                (MessageStatus.failed, last_seen_id, batch_size),
            )
            for row in rows:
                if self.closed:
                    # early exit if the manager is closed
                    return
                batch.append(
                    DBMessage(
                        id=row[0], type=row[1], json=row[2], status=MessageStatus.failed
                    )
                )

            if not batch:
                break

            last_seen_id = batch[-1].id
            yield batch

    def _mark_as_db_failed(self, message: str) -> None:
        self.status = ManagerStatus.error
        LOGGER.error(
            "Due to an internal error, some network resiliency features were disabled "
            "which could lead to data loss. Contact us at support@comet.com. Error details: %r",
            message,
        )


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
    messages.CreateAttachmentMessage.message_type: messages.CreateAttachmentMessage,
}


def db_message_to_message(db_message: DBMessage) -> messages.BaseMessage:
    message_class = SUPPORTED_MESSAGE_TYPES.get(db_message.type)
    if message_class is None:
        raise ValueError(f"Unsupported message type: {db_message.type}")

    return message_serialization.deserialize_message(
        message_class, json_str=db_message.json
    )
