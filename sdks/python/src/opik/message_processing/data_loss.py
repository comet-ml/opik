"""Tracking of terminally-dropped (never-delivered) messages.

The background sender never raises into user code and never blocks the app's
critical path. That leaves a gap: when a batch of traces/spans is dropped after
all retries and recovery are exhausted, the caller has no in-band way to learn
about it. :class:`DataLossTracker` records those terminal drops so they can be
surfaced through :class:`FlushResult` when the caller flushes or ends the client.

Only *terminal* drops are recorded here — messages that will never be sent
again. Transient states that are still expected to be delivered (rate-limit
re-enqueues, connection errors parked for replay) are deliberately excluded.
"""

import collections
import dataclasses
import enum
import threading
import time
from typing import Deque, List, Optional, Tuple


class FailureReason(str, enum.Enum):
    HTTP_CLIENT_ERROR = "http_client_error"
    HTTP_SERVER_ERROR = "http_server_error"
    UNAUTHORIZED = "unauthorized"
    SERIALIZATION = "serialization"
    UNKNOWN = "unknown"

    @staticmethod
    def from_status_code(status_code: Optional[int]) -> "FailureReason":
        if status_code is not None and 400 <= status_code < 500:
            return FailureReason.HTTP_CLIENT_ERROR
        if status_code is not None and 500 <= status_code < 600:
            return FailureReason.HTTP_SERVER_ERROR
        return FailureReason.UNKNOWN


@dataclasses.dataclass(frozen=True)
class FailedMessageInfo:
    """A single terminal drop: one message the SDK gave up on delivering."""

    message_type: str
    reason: FailureReason
    item_count: int
    status_code: Optional[int] = None
    detail: Optional[str] = None
    timestamp: float = dataclasses.field(default_factory=time.time)


@dataclasses.dataclass(frozen=True)
class FlushResult:
    """Outcome of a ``flush()``/``end()`` call.

    Attributes:
        flushed: Whether the queue drained within the timeout.
        remaining_queue_size: Messages still queued when the call returned.
        dropped_messages: Terminally-dropped messages observed during this flush.
        dropped_items: Traces/spans lost across those dropped messages.
        failures: Details of the drops observed during this flush (best-effort;
            bounded by the tracker's capacity).
    """

    flushed: bool
    remaining_queue_size: int
    dropped_messages: int
    dropped_items: int
    failures: List[FailedMessageInfo]

    @property
    def success(self) -> bool:
        """True only if this flush drained the queue with no data loss."""
        return self.flushed and self.dropped_messages == 0


class DataLossTracker:
    """Thread-safe, bounded record of terminally-dropped messages.

    Shared across all :class:`opik.Opik` handles on one connection identity (the
    background sender is shared). Per-flush attribution is done with an opaque
    monotonic marker: :meth:`marker` before the flush, :meth:`drops_since`
    after.
    """

    def __init__(self, max_entries: int = 1000):
        self._lock = threading.Lock()
        self._entries: Deque[FailedMessageInfo] = collections.deque(maxlen=max_entries)
        self._recorded_count = 0

    def record(self, failure: FailedMessageInfo) -> None:
        with self._lock:
            self._entries.append(failure)
            self._recorded_count += 1

    def marker(self) -> int:
        with self._lock:
            return self._recorded_count

    def drops_since(self, marker: int) -> Tuple[int, List[FailedMessageInfo]]:
        """Drops recorded since ``marker``, as one consistent snapshot.

        Returns the exact count and the retained details. The count is always
        exact; the details are best-effort — under extreme drop volume the
        oldest entries are evicted, so fewer than ``count`` may be returned.
        A single lock keeps count and details consistent even while other
        clients on the shared sender keep recording.
        """
        with self._lock:
            count = self._recorded_count - marker
            window_size = min(count, len(self._entries))
            failures = list(self._entries)[-window_size:] if window_size > 0 else []
            return count, failures
