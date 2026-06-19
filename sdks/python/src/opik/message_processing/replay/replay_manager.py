import logging
import threading
import time
from typing import Optional

from opik.healthcheck import connection_monitor
from . import db_manager, file_lock, types
from .. import messages

LockHandleType = Optional[file_lock.FileLock]


LOGGER = logging.getLogger(__name__)


def _check_message_id(message_id: Optional[int]) -> None:
    if message_id is None:
        # fail fast - it is a programming error if message ID is not provided
        raise ValueError("Message ID expected")


class ReplayManager(threading.Thread):
    """
    Manages replaying failed messages to the Opik server.

    The manager runs a background thread that monitors the connection to the
    OPIK server. When the connection is restored, only the process that holds
    the replay leader lock replays failed messages from the shared SQLite file.
    If the leader dies, another process can acquire the lock on a subsequent tick
    and take over replay duties.

    When no persistent ``db_file`` is configured, a temporary file is used and
    this process is always treated as the replay leader.
    """

    def __init__(
        self,
        monitor: connection_monitor.OpikConnectionMonitor,
        batch_size: int,
        batch_replay_delay: float,
        tick_interval_seconds: float,
        db_file: Optional[str] = None,
    ):
        """
        Args:
            monitor: Connection monitor used to detect server connectivity.
            batch_size: Number of failed messages to replay in one batch.
            batch_replay_delay: Delay in seconds between replay batches.
            tick_interval_seconds: Interval between thread ticks.
            db_file: Optional path to a persistent SQLite file. Multiple processes
                can share this file using SQLite WAL mode.
        """
        super().__init__(daemon=True, name="ReplayManager")
        self._stop_running = threading.Event()
        self._monitor = monitor
        self._replay_callback: Optional[types.ReplayCallback] = None
        self._tick_interval_seconds = tick_interval_seconds
        self._next_tick_time = time.time() + self._tick_interval_seconds

        self._db_file = db_file
        self._db_manager = db_manager.DBManager(
            batch_size=batch_size,
            batch_replay_delay=batch_replay_delay,
            db_file=db_file,
        )

        self._replay_lock: LockHandleType = None
        self._is_replay_leader = False
        self._try_acquire_replay_lock()

    def _try_acquire_replay_lock(self) -> None:
        """Attempt to become the replay leader. Always leader in temp-file mode."""
        if self._db_file is None:
            self._is_replay_leader = True
            return

        if self._is_replay_leader:
            return

        lock = file_lock.acquire_replay_lock(self._db_file)
        if lock is not None:
            self._replay_lock = lock
            self._is_replay_leader = True
            LOGGER.debug("This process is now the replay leader for %s", self._db_file)

    def _release_replay_lock(self) -> None:
        if self._replay_lock is not None:
            file_lock.release_replay_lock(self._replay_lock)
            self._replay_lock = None
        self._is_replay_leader = False

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
    def is_replay_leader(self) -> bool:
        """Returns True if this process currently holds the replay leader lock."""
        return self._is_replay_leader

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
        """Stop the replay manager and release locks."""
        self._stop_running.set()

        # Wait for the background thread to actually exit before releasing the
        # cross-process replay lock. If the thread is still inside
        # _replay_failed_messages(), releasing the lock now would allow another
        # process to acquire leadership and start replaying the same shared db.
        if self.ident is not None and self.is_alive():
            self.join()

        # The thread's run() finally block closes the DBManager. If the thread
        # was never started, close it here.
        if not self._db_manager.closed:
            self._db_manager.close()

        self._release_replay_lock()

    def flush(self) -> None:
        """Force replay of all failed messages to the server if this is the leader."""
        self._check_replay_callback()
        if self._is_replay_leader:
            self._replay_failed_messages()
        else:
            LOGGER.debug("flush skipped: this process is not the replay leader")

    def _loop(self) -> None:
        sleep_time = self._next_tick_time - time.time()
        if sleep_time > 0:
            # sleep until the next tick time to avoid excessive CPU usage - interruptible-sleep by close
            self._stop_running.wait(sleep_time)

        try:
            status = self._monitor.tick()

            # Allow leadership to move if the current leader dies.
            if not self._is_replay_leader:
                self._try_acquire_replay_lock()

            if status == connection_monitor.ConnectionStatus.connection_restored:
                if self._is_replay_leader:
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
