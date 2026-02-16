import time
from enum import Enum
from typing import Optional

from opik.healthcheck import connection_probe


class ConnectionStatus(Enum):
    """
    Represents the connection status for a system.

    Provides an enumeration of various states a connection can be in, such as
    successful connection, connection failure, and restored connection.
    Used primarily to indicate the status of a network, server, or other connectivity-dependent services.

    Attributes:
        connection_ok: Indicates the connection is currently active and functioning properly.
        connection_failed: Indicates the connection has failed.
        connection_restored: Indicates the connection, which was previously failed, has been restored.
    """

    connection_ok = 1
    connection_failed = 2
    connection_restored = 3


class OpikConnectionMonitor:
    """
    Monitors the connection to the Opik server and tracks its status.

    This class is responsible for checking the server connectivity using a probe object
    at regular intervals. It maintains the status of the server connection, including
    disconnection details and reasons.

    Attributes:
        ping_interval: Interval in seconds between server connection pings.
        disconnect_time: Time in seconds when the server connection was lost.
            Defaults to 0 if the connection has not been lost yet.
        disconnect_reason: Reason for the disconnection, if applicable.
            Defaults to None if there is no disconnection.
    """

    def __init__(
        self,
        ping_interval: float,
        check_timeout: float,
        probe: connection_probe.ConnectionProbe,
    ):
        """
        Initializes an instance of the class to handle connection probing and monitoring.

        Args:
            ping_interval: The interval, in seconds, at which pings are sent to probe
                the connection.
            check_timeout: The threshold duration, in seconds, before a connection is
                considered lost if no response is received.
            probe: An instance of the ``connection_probe.ConnectionProbe`` class used for
                sending probe requests and handling connectivity checks.
        """
        self._check_timeout = check_timeout
        self._has_server_connection = True
        self._probe = probe

        self.last_beat = float("-inf")
        self.ping_interval = ping_interval
        self.disconnect_time = 0.0
        self.disconnect_reason: Optional[str] = None

    @property
    def has_server_connection(self) -> bool:
        return self._has_server_connection

    def reset(self) -> None:
        self.disconnect_time = 0.0
        self.disconnect_reason = None
        self._has_server_connection = True

    def connection_failed(self, failure_reason: Optional[str]) -> None:
        """
        Handles the procedure when the connection to the server is determined to have failed.

        This method processes the failure by capturing the disconnection time and the reason for the failure
        (if provided). It also updates the state to reflect that the connection to the server is no longer active.

        Args:
            failure_reason: Optional reason for the connection failure. Defaults to None if no reason
                is provided.
        """
        if self._has_server_connection:
            # save the first disconnection time and reason
            self.disconnect_time = time.time()
            self.disconnect_reason = failure_reason

        self._has_server_connection = False

    def tick(self) -> ConnectionStatus:
        """
        Checks and updates the connection status during a timed heartbeat event.

        This method ensures that the connection is actively monitored, sends probe
        requests to check the connection status if necessary, and updates the last
        heartbeat timestamp.

        Returns:
            ConnectionStatus: Indicates the current status of the connection. It may
            return one of the following statuses:
            - `ConnectionStatus.connection_ok`: The connection is active.
            - `ConnectionStatus.connection_failed`: The connection is not active.
            - Result based on the probe check indicating whether the connection is
              healthy or not.
        """
        next_beat = self.last_beat + self.ping_interval
        now = time.time()
        if next_beat <= now:
            result = self._probe.check_connection(timeout=self._check_timeout)
            self.last_beat = time.time()
            return self._on_ping_result(
                result.is_healthy, failure_reason=result.error_message
            )
        elif self.has_server_connection:
            return ConnectionStatus.connection_ok
        else:
            return ConnectionStatus.connection_failed

    def _on_ping_result(
        self, success: bool, failure_reason: Optional[str]
    ) -> ConnectionStatus:
        """Invoked to process a received ping result"""
        if success:
            if not self.has_server_connection:
                status = ConnectionStatus.connection_restored
            else:
                status = ConnectionStatus.connection_ok

            self._has_server_connection = True
        else:
            status = ConnectionStatus.connection_failed
            self.connection_failed(failure_reason)

        return status
