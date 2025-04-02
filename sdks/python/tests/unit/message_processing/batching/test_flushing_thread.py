import time
from unittest import mock
from opik.message_processing.batching import (
    batchers,
    flushing_thread,
)


def test_flushing_thread__batcher_is_flushed__every_time_flush_interval_time_passes():
    flush_callback = mock.Mock()
    FLUSH_INTERVAL = 0.2
    very_big_batch_size = float("inf")
    batcher = batchers.CreateSpanMessageBatcher(
        flush_callback=flush_callback,
        max_batch_size=very_big_batch_size,
        flush_interval_seconds=FLUSH_INTERVAL,
    )
    tested = flushing_thread.FlushingThread(batchers=[batcher])

    tested.start()
    batcher.add("some-value-to-make-batcher-not-empty")
    flush_callback.assert_not_called()

    time.sleep(FLUSH_INTERVAL + 0.1)
    # flush interval has passed after batcher was created, batcher is ready to be flushed
    # (0.1 is added because thread probation interval is 0.1 and it's already made it first check)
    flush_callback.assert_called_once()

    flush_callback.reset_mock()

    batcher.add("some-value-to-make-batcher-not-empty")
    time.sleep(FLUSH_INTERVAL)
    # flush interval has passed after previous flush, batcher is ready to be flushed again
    flush_callback.assert_called_once()
