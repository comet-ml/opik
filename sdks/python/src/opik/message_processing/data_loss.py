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
    failures: Tuple[FailedMessageInfo, ...]

    @property
    def success(self) -> bool:
        """True only if this flush drained the queue with no data loss."""
        return self.flushed and self.dropped_messages == 0


@dataclasses.dataclass(frozen=True)
class ErrorsReport:
    """Snapshot of terminal data loss recorded by the background sender.

    Sender-wide and not tied to a single flush.

    .. note::
        The report is **capped**. ``total_dropped_messages`` /
        ``total_dropped_items`` are always exact (kept as running totals), but
        ``failures`` holds only a bounded, most-recent window of the per-drop
        details. Once that limit is reached the oldest details are discarded, so
        ``failures`` can contain fewer entries than ``total_dropped_messages``,
        and ``first_failure_at`` reflects the oldest *retained* detail, not
        necessarily the first-ever drop.

    Attributes:
        total_dropped_messages: Messages terminally dropped since the sender
            started (exact).
        total_dropped_items: Traces/spans lost across those messages (exact).
        failures: Most-recent per-drop details, capped (see note); each carries
            its own ``timestamp``.
        generated_at: Unix time when this report was produced.
    """

    total_dropped_messages: int
    total_dropped_items: int
    failures: Tuple[FailedMessageInfo, ...]
    generated_at: float

    @property
    def first_failure_at(self) -> Optional[float]:
        """Timestamp of the oldest retained failure (bounded window), or None."""
        return min((failure.timestamp for failure in self.failures), default=None)

    @property
    def last_failure_at(self) -> Optional[float]:
        """Timestamp of the newest retained failure, or None."""
        return max((failure.timestamp for failure in self.failures), default=None)


class DataLossTracker:
    """Bounded record of terminally-dropped messages.

    Shared across all :class:`opik.Opik` handles on one connection identity (the
    background sender is shared). Per-flush attribution is done with an opaque
    monotonic marker: :meth:`marker` before the flush, :meth:`drops_since`
    after.

    Lock-free: writes come from the sender's background threads and rely on
    ``deque`` being thread-safe. The running counters are plain integers, so
    under concurrent drops a count may momentarily lag or round off — that
    imprecision is deliberately accepted (a data-loss tally need not be exact to
    the message, and a lock would add contention on the hot sending path).
    """

    def __init__(self, max_entries: int = 1000):
        self._entries: Deque[FailedMessageInfo] = collections.deque(maxlen=max_entries)
        # Running totals kept independently of the bounded ``_entries`` window,
        # so counts survive eviction of the oldest details.
        self._recorded_count = 0
        self._recorded_items = 0

    def record(self, failure: FailedMessageInfo) -> None:
        self._entries.append(failure)
        self._recorded_count += 1
        self._recorded_items += failure.item_count

    def marker(self) -> DropMarker:
        """Opaque token marking the current point in the drop history.

        Carries the running (message, item) totals; pass it to
        :meth:`drops_since` to get the deltas observed since.
        """
        return self._recorded_count, self._recorded_items

    def drops_since(
        self, marker: DropMarker
    ) -> Tuple[int, int, List[FailedMessageInfo]]:
        """Drops recorded since ``marker``.

        Returns ``(message_count, item_count, failures)`` — the counts from the
        running totals, and the retained per-drop details (bounded to the most
        recent ``max_entries``, oldest evicted once capacity is exceeded).
        """
        marker_count, marker_items = marker
        count = self._recorded_count - marker_count
        items = self._recorded_items - marker_items
        window = list(self._entries)
        window_size = min(count, len(window))
        failures = window[-window_size:] if window_size > 0 else []
        return count, items, failures

    def total_drops(self) -> Tuple[int, int, List[FailedMessageInfo]]:
        """All-time drop totals plus retained details.

        Returns ``(message_count, item_count, failures)``, independent of any
        flush boundary — answers "has anything been lost?" across the sender's
        lifetime. ``failures`` is bounded to the most recent ``max_entries``,
        older details evicted.
        """
        return self._recorded_count, self._recorded_items, list(self._entries)
