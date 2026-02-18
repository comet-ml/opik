"""On-disk SQLite message store used by the replay flow to persist messages
that failed to reach the Opik server. DBManager exposes helpers for the full
message lifecycle: register_message(s), fetch_failed_messages_batched, and
replay_failed_messages (which accepts a ReplayCallback, typically Streamer.put,
to re-inject messages). The manager tracks three states — initialized, closed,
and failed — and if the underlying database becomes unavailable, it marks itself
as failed and logs that resiliency features are disabled. The standalone helper
db_message_to_message raises ValueError for unsupported message types.
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
class DBManagerStatus(IntEnum):
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


def _preprocess_registered_message(message: messages.BaseMessage) -> str:
    if message.message_id is None:
        raise ValueError("Message ID expected")

    return message_serialization.serialize_message(message)


class DBManager:
    """
    Manages message storage, batch processing, and database operations within a replay
    system.

    This class provides functionalities to handle message registrations, batch updates,
    and database schema management. It ensures thread-safe operations, cleans up
    temporary files, and handles database connections as part of its lifecycle.

    """

    def __init__(
        self,
        batch_size: int,
        batch_replay_delay: float,
        db_file: Optional[str] = None,
        conn: Optional[sqlite3.Connection] = None,
        sync_lock: Optional[threading.RLock] = None,
    ) -> None:
        """
        Initializes the Manager class, setting up the database connection, temporary
        directory, and other necessary properties for managing batch operations.

        Args:
            batch_size: The size of batches for processing. Defaults to
                DEFAULT_BATCH_SIZE.
            batch_replay_delay: The delay (in seconds) between replaying batches of messages.
            db_file: Path to the database file. If not provided, a
                temporary file will be created in a temporary directory.
            conn: A pre-existing database connection.
                If not provided, a new connection is created.
        """
        self.batch_size = batch_size
        self.batch_replay_delay = batch_replay_delay
        self.status = DBManagerStatus.undefined
        self.tmp_dir = tempfile.mkdtemp()
        if db_file is None:
            db_file = os.path.join(self.tmp_dir, DEFAULT_DB_FILE)

        self.db_file = db_file
        # open DB connection if appropriate
        if conn is None:
            conn = sqlite3.connect(self.db_file, check_same_thread=False)
        self.conn = conn

        if sync_lock is None:
            self.__lock__ = threading.RLock()
        else:
            self.__lock__ = sync_lock
        self._create_db_schema()

    def _create_db_schema(self) -> None:
        try:
            with self.__lock__:
                with self.conn:
                    self.conn.execute(
                        """CREATE TABLE IF NOT EXISTS messages
                                            (message_id INTEGER NOT NULL PRIMARY KEY,
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
        """
        Closes the current manager, releasing all associated resources.

        This method ensures the proper cleanup of resources such as database connections
        and temporary directories. It performs the operations within a thread-safe context
        by acquiring an internal lock. If the manager is already closed, the method will
        return immediately without performing any actions.
        """
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
        """
        Registers a message into the database if the system is initialized and not closed.

        This method processes the given message, converts it into a JSON format, and
        inserts it into the database with the provided or default status. If the system
        is either not initialized or already closed, the message registration will be
        ignored.

        Args:
            message: The message object to be registered. This
                object must have attributes such as `message_id` and `message_type`.
            status: The status of the message to be registered.
                Defaults to `MessageStatus.registered`.
        """
        if not self.initialized:
            LOGGER.debug("Not initialized - register message ignored")
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - register message ignored")
                return

            message_json = _preprocess_registered_message(message)
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
                self._mark_as_db_failed(
                    f"register_message: failed to insert message into DB, reason: {ex}"
                )
                raise

    def register_messages(
        self,
        messages_batch: List[messages.BaseMessage],
        status: MessageStatus = MessageStatus.registered,
    ) -> None:
        """
        Registers a batch of messages in the database. If the system is not initialized or
        already closed, the operation is ignored.

        Args:
            messages_batch: A list of message objects to be
                registered in the database.
            status: The status to be associated with the registered messages.
                Defaults to MessageStatus.registered.
        """
        if not self.initialized:
            LOGGER.debug("Not initialized - register messages list ignored")
            return

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - register messages list ignored")
                return

            values = []
            for message in messages_batch:
                message_json = _preprocess_registered_message(message)
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
                self._mark_as_db_failed(
                    f"register_messages: failed to insert messages into DB, reason: {ex}"
                )
                raise

    def update_messages_batch(
        self, message_ids: List[int], status: MessageStatus
    ) -> None:
        """
        Updates the status of a batch of messages in the messages database table or deletes
        the messages if their status is 'delivered'. The function ensures thread safety
        and performs operations only if the instance is initialized and not closed.

        Args:
            message_ids: A list of message IDs to be updated or deleted.
            status: The new status to be assigned to the messages or to
                determine if messages should be deleted when the status is 'delivered'.

        """
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
        """
        Updates the status of a message in the database or removes it if delivered.

        This method is responsible for updating the status of a message in the database
        or removing delivered messages along with their associated data. The operation
        is ignored if the instance is not initialized or already closed.

        Args:
            message_id: The unique identifier of the message to be updated.
            status: The new status to set for the message. If the status
                is `MessageStatus.delivered`, the message will be removed.
        """
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
        Replays previously failed messages by fetching them in batches, marking them as
        "in progress," and invoking the provided callback for processing.

        This method processes messages marked as failed in the database by updating
        their status and invoking the replay callback. It ensures thread safety and supports
        batch processing to limit memory usage. If the system is not initialized or already
        closed, the replay process is skipped. Errors encountered during the replay or while
        updating the database are logged, and the replay process halts.

        Args:
            replay_callback: A callback function that processes the
                replayed messages.

        Returns:
            int: The total count of successfully replayed messages.
        """
        if not self.initialized:
            LOGGER.debug("Not initialized - messages replay ignored")
            return 0

        with self.__lock__:
            if self.closed:
                LOGGER.warning("Already closed - messages replay ignored")
                return 0

            total_replayed = 0
            for db_messages in self.fetch_failed_messages_batched(self.batch_size):
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
                    self._mark_as_db_failed(
                        f"replay_failed_messages: failed to update messages in the DB, reason: {ex}"
                    )
                    raise

                replayed = self._replay_messages(
                    db_messages=db_messages, replay_callback=replay_callback
                )
                total_replayed += replayed
                # sleep to allow consumption of the messages batch and to avoid OOM (when subsequent batches loaded)
                # applied only if full batch_size was replayed to avoid delays after the last batch or for smaller batches
                if replayed >= self.batch_size:
                    time.sleep(self.batch_replay_delay)

            return total_replayed

    def _replay_messages(
        self, db_messages: List[DBMessage], replay_callback: ReplayCallback
    ) -> int:
        LOGGER.debug("Replaying %d failed messages to streamer", len(db_messages))
        replayed = 0
        for message in db_messages:
            try:
                base_message = db_message_to_message(message)
                replay_callback(base_message)
                replayed += 1
            except Exception as e:
                LOGGER.error(
                    "Failed to replay message with id=%r, type=%r, status=%r, reason: %s",
                    message.id,
                    message.type,
                    message.status,
                    e,
                    exc_info=True,
                )
                # mark the message as failed in the DB
                self.update_message(message.id, MessageStatus.failed)

        return replayed

    def get_message(self, message_id: int) -> Optional[messages.BaseMessage]:
        """
        Fetches a message by its unique identifier.

        This method retrieves a message from the database using the provided message ID. If a corresponding
        database message is found, it converts the database message to a message object and returns it.
        If no message is found for the given ID, the method returns None.

        Args:
            message_id: The unique identifier of the message to retrieve.

        Returns:
            The converted message object if found, otherwise None.
        """
        db_message = self.get_db_message(message_id)
        if db_message is not None:
            return db_message_to_message(db_message)
        else:
            return None

    def get_db_message(self, message_id: int) -> Optional[DBMessage]:
        """
        Retrieves a database message based on the provided message ID.

        This method queries the database for a message record that corresponds to the
        given message ID. If a matching message record is found, it constructs and
        returns a `DBMessage` object containing the message's details. If no record is
        found, it returns `None`.

        Args:
            message_id: The ID of the message to retrieve.

        Returns:
            A `DBMessage` object containing the details of the
            retrieved message if found, otherwise `None`.
        """
        with self.conn:
            c = self.conn.execute(
                "SELECT message_id, message_type, message_json, status FROM messages WHERE message_id = ?",
                (message_id,),
            )
            row = c.fetchone()
            if row is not None:
                return DBMessage(
                    id=row[0], type=row[1], json=row[2], status=MessageStatus(row[3])
                )
            else:
                return None

    @property
    def closed(self) -> bool:
        """
        Determines if the manager is currently closed.

        This property evaluates whether the manager's `status` is equivalent to
        `ManagerStatus.closed`. If so, it indicates that the manager is not
        active or operational.

        Returns:
            bool: True if the manager's status is `ManagerStatus.closed`, False otherwise.
        """
        return self.status == DBManagerStatus.closed

    @property
    def initialized(self) -> bool:
        """
        Indicates whether the manager's status is currently set to 'initialized'.

        This property provides a lookup to determine if the manager is currently in the
        initialized state, based on its status attribute.

        Returns:
            bool: True if the manager's status is 'initialized', False otherwise.
        """
        return self.status == DBManagerStatus.initialized

    @property
    def failed(self) -> bool:
        """
        Checks if the current status indicates a failure.

        The `failed` property evaluates whether the `status` attribute of the
        manager is equal to `ManagerStatus.error`. This serves as an indicator
        of whether an error state has been encountered.

        Returns:
            bool: True if the status is `ManagerStatus.error`, otherwise False.
        """
        return self.status == DBManagerStatus.error

    def fetch_failed_messages_batched(
        self, batch_size: int
    ) -> Iterator[List[DBMessage]]:
        """Fetch failed messages from DB in bounded batches to avoid OOM.

        Uses cursor-based pagination with message_id for efficient iteration.
        Yields batches of DBMessage objects until no more failed messages remain.
        """
        last_seen_id = -1
        while True:
            batch: List[DBMessage] = []
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
                        id=row[0], type=row[1], json=row[2], status=MessageStatus.failed
                    )
                )

            if not batch:
                break

            last_seen_id = batch[-1].id
            yield batch

    def failed_messages_count(self) -> int:
        """Returns the number of failed messages in the DB."""
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

    def _mark_as_db_failed(self, message: str) -> None:
        self.status = DBManagerStatus.error
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
    """
    Converts a database message object to a corresponding application message object.

    This function maps a database message type to its associated application message
    class using a predefined dictionary of supported message types. If the type is not
    supported, an exception is raised. The message is then deserialized from its JSON
    representation into the appropriate application message class.

    Args:
        db_message: The database message object containing the type and serialized JSON
            data to be deserialized.

    Returns:
        The deserialized application message object corresponding to the provided
        database message type.

    Raises:
        ValueError: If the database message contains an unsupported or unrecognized
        message type.
    """
    message_class = SUPPORTED_MESSAGE_TYPES.get(db_message.type)
    if message_class is None:
        raise ValueError(f"Unsupported message type: {db_message.type}")

    return message_serialization.deserialize_message(
        message_class, json_str=db_message.json
    )
