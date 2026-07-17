"""Assembles :class:`~opik.message_processing.data_loss.FlushResult` values.

The read side of data-loss reporting. It holds the two collaborators needed to
describe a flush — the queue (via the streamer) and the :class:`DataLossTracker`
— so that no caller has to gather them itself. One instance is owned per
connection bundle and shared by every client on it.
"""

import logging
from typing import TYPE_CHECKING

from . import data_loss

if TYPE_CHECKING:
    from . import streamer as streamer_module


LOGGER = logging.getLogger(__name__)


class FlushReporter:
    def __init__(
        self,
        streamer: "streamer_module.Streamer",
        data_loss_tracker: data_loss.DataLossTracker,
    ) -> None:
        self._streamer = streamer
        self._data_loss_tracker = data_loss_tracker

    def marker(self) -> "data_loss.DropMarker":
        """Opaque token identifying the current point in the drop history.

        Take one before a flush; pass it to :meth:`build_result` afterwards to
        attribute only the drops observed in between to that flush.
        """
        return self._data_loss_tracker.marker()

    def build_result(
        self, marker: "data_loss.DropMarker", *, flushed: bool
    ) -> "data_loss.FlushResult":
        dropped_messages, dropped_items, failures = self._data_loss_tracker.drops_since(
            marker
        )
        result = data_loss.FlushResult(
            flushed=flushed,
            remaining_queue_size=self._streamer.queue_size(),
            dropped_messages=dropped_messages,
            dropped_items=dropped_items,
            failures=failures,
        )
        if not result.success:
            LOGGER.error(
                "Opik flush completed with data loss: %d message(s) / %d item(s) "
                "dropped, %d still queued. Inspect Opik.last_flush_result for details.",
                result.dropped_messages,
                result.dropped_items,
                result.remaining_queue_size,
            )
        return result
