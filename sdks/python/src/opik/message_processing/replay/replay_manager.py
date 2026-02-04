import json
import logging
import os
import shutil
import sqlite3
import tempfile
import threading
from enum import unique, IntEnum
from typing import List, NamedTuple, Callable, Optional, Dict

from opik.message_processing import messages

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
    def __init__(
        self, db_file: Optional[str] = None, conn: Optional[sqlite3.Connection] = None
    ) -> None:
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
                        """CREATE TABLE messages
                                            (message_id INTEGER NOT NULL PRIMARY KEY,
                                            status INTEGER NOT NULL,
                                            message_type TEXT NOT NULL,
                                            message_json TEXT NOT NULL)"""
                    )
                    self.status = ManagerStatus.initialized
        except Exception as ex:
            msg = "Database schema creation failed, reason: %r" % ex
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
            except Exception:
                LOGGER.debug("Failed to close messages DB connection", exc_info=True)

            # delete temporary data
            if self.tmp_dir is not None:
                try:
                    LOGGER.debug("Cleaning temporary data dir: %r", self.tmp_dir)
                    shutil.rmtree(self.tmp_dir)
                except Exception:
                    LOGGER.debug(
                        "Failed to clean temporary data dir: %r",
                        self.tmp_dir,
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
        messages: List[messages.BaseMessage],
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
            for message in messages:
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
                msg = (
                    "register_messages: failed to insert messages into DB, reason: %r"
                    % ex
                )
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)

    def _clean_message_leftovers(self, message_id: int) -> None:
        # Cleanup message file
        if message_id in self.message_files:
            try:
                os.remove(self.message_files[message_id])
            except Exception:
                LOGGER.debug(
                    "Failed to remove temporary file: %r of the message: %d",
                    self.message_files[message_id],
                    message_id,
                    exc_info=True,
                )

    def _preprocess_registered_message(self, message: messages.BaseMessage) -> str:
        if message.message_id is None:
            raise ValueError("Message ID expected")

        if isinstance(message, messages.CreateAttachmentMessage):
            self.message_files[message.message_id] = message.file_path

        return json.dumps(
            message.as_db_message_dict(),
            sort_keys=True,
        )

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
                msg = (
                    "update_messages_batch: failed to update messages batch in the DB, reason: %r"
                    % ex
                )
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
                    "update_message: failed to update message in the DB, reason: %r"
                    % ex
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

            try:
                db_messages = self._fetch_failed_messages()
                if len(db_messages) == 0:
                    return 0
            except Exception as ex:
                msg = (
                    "replay_failed_messages: failed to fetch failed messages from DB, reason: %r"
                    % ex
                )
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)
                return 0

            messages_ids = [(message.id,) for message in db_messages]
            # update DB records to mark failed messages as in progress
            try:
                with self.conn:
                    c = self.conn.executemany(
                        "UPDATE messages SET status = %d WHERE message_id = ?"
                        % MessageStatus.registered,
                        messages_ids,
                    )
                    LOGGER.debug(
                        "Updated %d DB message records for %d failed messages",
                        c.rowcount,
                        len(db_messages),
                    )
            except Exception as ex:
                msg = (
                    "replay_failed_messages: failed to update messages in the DB, reason: %r"
                    % ex
                )
                self._mark_as_db_failed(msg)
                LOGGER.debug(msg, exc_info=True)
        return self._replay_messages(
            db_messages=db_messages, replay_callback=replay_callback
        )

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
            except Exception:
                LOGGER.error("Failed to replay message: %r", message)

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

    def _fetch_failed_messages(self) -> List[DBMessage]:
        messages_db = []
        for row in self.conn.execute(
            "SELECT message_id, message_type, message_json FROM messages WHERE status = ?",
            (MessageStatus.failed,),
        ):
            messages_db.append(
                DBMessage(
                    id=row[0], type=row[1], json=row[2], status=MessageStatus.failed
                )
            )

        return messages_db

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
    messages.AttachmentSupportingMessage.message_type: messages.AttachmentSupportingMessage,
}


def db_message_to_message(db_message: DBMessage) -> messages.BaseMessage:
    message_dict = json.loads(db_message.json)

    message_class = SUPPORTED_MESSAGE_TYPES.get(db_message.type)
    if message_class is None:
        raise ValueError(f"Unsupported message type: {db_message.type}")

    return messages.from_db_message_dict(message_class, message_dict)
