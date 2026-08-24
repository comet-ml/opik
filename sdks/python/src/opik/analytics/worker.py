import logging
import queue
import threading
from typing import Any, Callable, Dict, List, NamedTuple, Optional, Union

from .. import environment_details

LOGGER = logging.getLogger(__name__)

PropertyValue = Union[str, int, float, bool, None]
"""
What an event property may hold. Scalars only, on purpose: it keeps payloads
reviewable at the call site and makes it impossible to pass a span, a prompt or
any other object that happens to carry user data.
"""


class Event(NamedTuple):
    name: str
    properties: Dict[str, PropertyValue]


def session_properties() -> Dict[str, PropertyValue]:
    """
    Properties describing the process, attached to every event.

    Deliberately the same set error reports carry - tags (OS, Python version, release,
    where it is running) plus context (verbose versions, session id, versions of the
    LLM libraries installed alongside Opik). Both read `environment_details`, so the
    two payloads cannot drift apart and the `session_id` on an event matches the one on
    any error report from the same run.

    Both collectors below are themselves cached, so this is a dict merge - and
    reporting is once-per-event-per-process, so it happens a bounded number of
    times. Caching it again here would only add a second thing to reset on fork.
    """
    properties: Dict[str, PropertyValue] = {
        "sdk_language": "python",
        **environment_details.collect_tags_once(),
        **environment_details.collect_context_once(),
    }

    return properties


class ReportingRejected(Exception):
    """
    Raised by a sender when the destination has told us to stop, as opposed to a
    request that merely failed. Retrying will not help and the process gives up.
    """


class _Flush:
    """Queue marker asking the worker to send what it has and say when it's done."""

    def __init__(self) -> None:
        self.done = threading.Event()


class Worker(threading.Thread):
    """
    The only thread that touches the network for analytics.

    Callers only ever `enqueue()`, which is a non-blocking put that drops the
    event if the queue is full. Batching and sending happen here, so a slow or
    unreachable destination can never show up as latency in the user's code.
    """

    def __init__(
        self,
        send: Callable[[List[Event]], None],
        max_queue_size: int,
        max_batch_size: int,
        batch_timeout_seconds: float,
        on_rejected: Optional[Callable[[], None]] = None,
    ) -> None:
        super().__init__(daemon=True, name="opik-analytics-worker")
        self._send = send
        self._on_rejected = on_rejected
        self._max_batch_size = max_batch_size
        self._batch_timeout_seconds = batch_timeout_seconds
        self._queue: queue.Queue[Union[Event, _Flush]] = queue.Queue(
            maxsize=max_queue_size
        )
        self._stopped = threading.Event()

    def enqueue(self, event: Event) -> bool:
        """False when the queue was full and the event was dropped."""
        try:
            self._queue.put_nowait(event)
            return True
        except queue.Full:
            LOGGER.debug("Analytics queue is full, dropping event %s", event.name)
            return False

    def flush(self, timeout: Optional[float]) -> bool:
        """Waits for everything enqueued so far to be sent. False on timeout."""
        if self._stopped.is_set() or not self.is_alive():
            return False

        marker = _Flush()
        try:
            self._queue.put_nowait(marker)
        except queue.Full:
            return False

        return marker.done.wait(timeout)

    def close(self, timeout: Optional[float]) -> None:
        self.flush(timeout)
        self._stopped.set()

        # Wakes the thread from its `get()` so it notices `_stopped` instead of
        # idling until the batch timeout expires.
        try:
            self._queue.put_nowait(_Flush())
        except queue.Full:
            pass

    def run(self) -> None:
        batch: List[Event] = []
        markers: List[_Flush] = []

        while not self._stopped.is_set():
            try:
                item = self._queue.get(timeout=self._batch_timeout_seconds)
            except queue.Empty:
                self._send_batch(batch, markers)
                batch, markers = [], []
                continue

            if isinstance(item, _Flush):
                markers.append(item)
                self._send_batch(batch, markers)
                batch, markers = [], []
                continue

            batch.append(item)
            if len(batch) >= self._max_batch_size:
                self._send_batch(batch, markers)
                batch, markers = [], []

        self._send_batch(batch, markers)

    def _send_batch(self, batch: List[Event], markers: List[_Flush]) -> None:
        try:
            if batch:
                self._send([self._enrich(event) for event in batch])
        except ReportingRejected:
            # The destination has retired us. Stop the thread and tell the caller
            # side to stop handing us events, rather than spending the rest of the
            # process making requests that are already known to be unwanted.
            LOGGER.debug("Analytics reporting rejected by the destination, stopping")
            self._stopped.set()
            if self._on_rejected is not None:
                self._on_rejected()
        except Exception:
            LOGGER.debug(
                "Failed to report %d analytics event(s)", len(batch), exc_info=True
            )
        finally:
            for marker in markers:
                marker.done.set()

    def _enrich(self, event: Event) -> Event:
        properties: Dict[str, Any] = {**session_properties(), **event.properties}
        return event._replace(properties=properties)
