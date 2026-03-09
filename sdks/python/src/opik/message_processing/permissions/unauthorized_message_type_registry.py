import dataclasses
import threading
import time
from typing import Dict, Optional


@dataclasses.dataclass
class UnauthorizedMessageItem:
    """
    Represents an unauthorized message item with details of its type, last attempt,
    and retry count.

    This class is typically used to store and manage information about a specific
    unauthorized message, including the type of the message, the timestamp of the
    last attempt, and how many times the system has retried the operation.

    Attributes:
        message_type: The type or category of the unauthorized message.
        last_attempt_at: A timestamp indicating when the last attempt to
            process the message occurred.
        retry_count: The number of times the system has attempted to process
            the unauthorized message.
    """

    message_type: str
    last_attempt_at: float
    retry_count: int


class UnauthorizedMessageTypeRegistry:
    """
    Handles the registration and authorization status of message types based on retry
    intervals and count limits.

    This class is designed to manage unauthorized message types using a retry mechanism.
    Each message type is tracked for its last attempt time and retry count. Based on the
    defined maximum retry count and retry interval, it determines whether a message type
    is authorized or not.
    """

    def __init__(
        self, retry_interval_seconds: float, max_retry_count: Optional[int] = None
    ):
        """
        Initializes the instance with retry interval and maximum retry count.

        This constructor sets up the retry mechanism for storing unauthorized message
        items in the registry. The retry interval determines the delay between retries,
        and the maximum retry count specifies the limit of retries.

        Args:
            retry_interval_seconds: The interval, in seconds, between retry attempts.
            max_retry_count: The maximum number of retry attempts is allowed.
                If None, retries will not have an upper limit.
        """
        self.registry: Dict[str, UnauthorizedMessageItem] = {}
        self.retry_interval_seconds = retry_interval_seconds
        self.max_retry_count = max_retry_count

        self._lock = threading.RLock()

    def add(self, message_type: str, attempt_time: Optional[float] = None) -> None:
        """
        Adds a message to the registry or updates an existing one. This method tracks
        the type of unauthorized message, its last attempt timestamp, and the retry
        count. If the message type does not already exist in the registry, it is
        created and initialized. Otherwise, the existing message is updated with the
        new attempt time and retry count is incremented.

        Args:
            message_type: The type of the message being added or updated.
            attempt_time: Timestamp of the current attempt. If not
                provided, the current system time is used.
        """
        with self._lock:
            message = self.registry.get(message_type)
            if attempt_time is None:
                attempt_time = time.time()

            if message is None:
                message = UnauthorizedMessageItem(
                    message_type, last_attempt_at=attempt_time, retry_count=0
                )
            else:
                message.retry_count += 1
                message.last_attempt_at = attempt_time

            self.registry[message_type] = message

    def is_authorized(self, message_type: str) -> bool:
        """
        Checks if the message type is authorized for processing based on retry count
        and time interval constraints.

        The function determines whether a message type can proceed by evaluating its
        retry count against a maximum retry threshold and ensuring a minimum interval
        has passed since the last attempt.

        Args:
            message_type: The type of message to check for authorization.

        Returns:
            True if the message type is authorized, False otherwise.
        """
        with self._lock:
            item = self.registry.get(message_type)
            if item is None:
                return True

            if (
                self.max_retry_count is not None
                and item.retry_count >= self.max_retry_count
            ):
                return False

            if time.time() - item.last_attempt_at >= self.retry_interval_seconds:
                return True

            return False
