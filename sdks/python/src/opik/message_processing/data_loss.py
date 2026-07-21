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

# Opaque token returned by ``DataLossTracker.marker`` and passed back to
# ``drops_since``: the running (message, item) totals at a point in time.
DropMarker = Tuple[int, int]


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


@dataclasses.dataclass(frozen=True)
class ErrorsReport:
    """Snapshot of terminal data loss recorded by the background sender.

    Sender-wide and not tied to a single flush. ``total_*`` counts are exact
    (running totals); ``failures`` holds the retained per-drop details, bounded
    to the most recent entries, each carrying its own ``timestamp``.

    Attributes:
        total_dropped_messages: Messages terminally dropped since the sender started.
        total_dropped_items: Traces/spans lost across those messages.
        failures: Retained per-drop details (most recent; bounded).
        generated_at: Unix time when this report was produced.
    """

    total_dropped_messages: int
    total_dropped_items: int
    failures: List[FailedMessageInfo]
    generated_at: float

    @property
    def has_data_loss(self) -> bool:
        return self.total_dropped_messages > 0

    @property
    def first_failure_at(self) -> Optional[float]:
        """Timestamp of the oldest retained failure (bounded window), or None."""
        return min((failure.timestamp for failure in self.failures), default=None)

    @property
    def last_failure_at(self) -> Optional[float]:
        """Timestamp of the newest retained failure, or None."""
        return max((failure.timestamp for failure in self.failures), default=None)


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
        # Running totals kept independently of the bounded ``_entries`` window,
        # so both message and item counts stay exact even after eviction.
        self._recorded_count = 0
        self._recorded_items = 0

    def record(self, failure: FailedMessageInfo) -> None:
        with self._lock:
            self._entries.append(failure)
            self._recorded_count += 1
            self._recorded_items += failure.item_count

    def marker(self) -> DropMarker:
        """Opaque token marking the current point in the drop history.

        Carries the running (message, item) totals; pass it to
        :meth:`drops_since` to get the exact deltas observed since.
        """
        with self._lock:
            return self._recorded_count, self._recorded_items

    def drops_since(
        self, marker: DropMarker
    ) -> Tuple[int, int, List[FailedMessageInfo]]:
        """Drops recorded since ``marker``, as one consistent snapshot.

        Returns ``(message_count, item_count, failures)``. Both counts are
        exact — derived from running totals, not the window — even under extreme
        drop volume; only ``failures`` (the details) are best-effort, since the
        oldest entries are evicted once capacity is exceeded. A single lock keeps
        the counts and details consistent even while other clients on the shared
        sender keep recording.
        """
        marker_count, marker_items = marker
        with self._lock:
            count = self._recorded_count - marker_count
            items = self._recorded_items - marker_items
            window_size = min(count, len(self._entries))
            failures = list(self._entries)[-window_size:] if window_size > 0 else []
            return count, items, failures

    def total_drops(self) -> Tuple[int, int, List[FailedMessageInfo]]:
        """All-time drop totals plus retained details, as one snapshot.

        Returns ``(message_count, item_count, failures)``, independent of any
        flush boundary — answers "has anything been lost?" across the sender's
        lifetime. Counts are exact (running totals); ``failures`` is bounded to
        the most recent ``max_entries``, older details evicted.
        """
        with self._lock:
            return self._recorded_count, self._recorded_items, list(self._entries)
