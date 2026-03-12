import logging
import threading
import time
from typing import Optional

from opik.healthcheck import connection_monitor
from . import db_manager, types
from .. import messages


LOGGER = logging.getLogger(__name__)


def _check_message_id(message_id: Optional[int]) -> None:
    if message_id is None:
        # fail fast - it is a programming error if message ID is not provided
        raise ValueError("Message ID expected")


class ReplayManager(threading.Thread):
    """
    Manages replaying messages to the server for a connection management system.

    The ReplayManager is responsible for ensuring that messages which fail to be
    delivered due to dropped connections are replayed to the server
    when the connection is restored. It continuously monitors the connection status
    and triggers failed message replays as necessary. The class runs in its own
    thread, leveraging the threading.Thread base class.
    """

    def __init__(
        self,
        monitor: connection_monitor.OpikConnectionMonitor,
        batch_size: int,
        batch_replay_delay: float,
        tick_interval_seconds: float,
    ):
        """
        Initializes the ReplayManager instance.

        Creates the replay manager thread with a specified connection monitor and tick interval.

        Args:
            monitor: An instance of OpikConnectionMonitor used for monitoring the connection.
            batch_size: The size of batches for processing.
            batch_replay_delay: The delay (in seconds) between replaying batches of messages.
            tick_interval_seconds: Interval in seconds between execution ticks. Default is 0.3.
        """
        super().__init__(daemon=True, name="ReplayManager")
        self._stop_running = threading.Event()
        self._monitor = monitor
        self._replay_callback: Optional[types.ReplayCallback] = None
        self._tick_interval_seconds = tick_interval_seconds
        self._next_tick_time = time.time() + self._tick_interval_seconds

        self._next_message_id = 0
        self._message_id_lock = threading.RLock()

        self._db_manager = db_manager.DBManager(
            batch_size=batch_size,
            batch_replay_delay=batch_replay_delay,
        )

    def start(self) -> None:
        self._check_replay_callback()
        super().start()

    def run(self) -> None:
        try:
            while not self._stop_running.is_set():
                self._loop()
        finally:
            # release the database connection
            self._db_manager.close()

    @property
    def database_manager(self) -> db_manager.DBManager:
        return self._db_manager

    @property
    def has_server_connection(self) -> bool:
        """Checks if SDK has a connection to the OPIK server."""
        return self._monitor.has_server_connection

    def register_message(
        self,
        message: messages.BaseMessage,
        status: db_manager.MessageStatus = db_manager.MessageStatus.registered,
    ) -> None:
        """Registers a message to be replayed if the connection is lost."""
        with self._message_id_lock:
            # set message ID if not set yet
            if message.message_id is None:
                message.message_id = self._next_message_id
                self._next_message_id += 1

        try:
            self._db_manager.register_message(message, status=status)
        except Exception as ex:
            LOGGER.error(
                "Failed to register message for replay, reason: %s", ex, exc_info=True
            )

    def unregister_message(self, message_id: int) -> None:
        """Unregisters a message from being replayed if the connection is lost."""
        _check_message_id(message_id)
        try:
            self._db_manager.update_message(
                message_id, status=db_manager.MessageStatus.delivered
            )
        except Exception as ex:
            LOGGER.error(
                "Failed to un-register message from replay, reason: %s",
                ex,
                exc_info=True,
            )

    def message_sent_failed(
        self, message_id: int, failure_reason: Optional[str] = None
    ) -> None:
        """Notifies the manager that a message was not sent due to a connection failure."""
        _check_message_id(message_id)
        try:
            self._monitor.connection_failed(failure_reason=failure_reason)
            self._db_manager.update_message(
                message_id, status=db_manager.MessageStatus.failed
            )
        except Exception as ex:
            LOGGER.error(
                "Failed to mark message as send-failed in replay manager, reason: %s",
                ex,
                exc_info=True,
            )

    def set_replay_callback(self, callback: types.ReplayCallback) -> None:
        """Sets the callback to be invoked when replaying failed messages."""
        self._replay_callback = callback

    def close(self) -> None:
        """Stop the replay manager."""
        self._stop_running.set()

    def flush(self) -> None:
        """Force replay of all failed messages to the server."""
        self._check_replay_callback()
        # ignore MyPy check because already asserted above
        self._replay_failed_messages()

    def _loop(self) -> None:
        sleep_time = self._next_tick_time - time.time()
        if sleep_time > 0:
            # sleep until the next tick time to avoid excessive CPU usage - interruptible-sleep by close
            self._stop_running.wait(sleep_time)

        try:
            status = self._monitor.tick()
            if status == connection_monitor.ConnectionStatus.connection_restored:
                # the connection was restored, replay all failed messages
                self._replay_failed_messages()
                self._monitor.reset()
        except Exception as ex:
            LOGGER.warning(
                "Failed to tick the connection monitor or replay failed messages, will repeat. Reason: %s",
                ex,
                exc_info=ex,
            )

        # schedule the next tick after the potential replay above
        self._next_tick_time = time.time() + self._tick_interval_seconds

    def _check_replay_callback(self) -> None:
        if self._replay_callback is None:
            raise ValueError(
                "Replay callback must be set before starting the replay manager"
            )

    def _replay_failed_messages(self) -> None:
        self._check_replay_callback()
        try:
            # ignore MyPy check because already asserted above
            replayed = self._db_manager.replay_failed_messages(
                self._replay_callback  # type: ignore
            )

            if replayed > 0:
                LOGGER.info(
                    "Replayed %d message(s) that were not sent due to server connection issues",
                    replayed,
                )
        except Exception as ex:
            LOGGER.error(
                "Failed to replay failed messages, reason: %s", ex, exc_info=True
            )
