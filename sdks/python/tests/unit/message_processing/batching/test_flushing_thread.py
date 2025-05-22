import time
from unittest import mock
from opik.message_processing.batching import (
    batchers,
    flushing_thread,
)
from ....testlib import fake_message_factory


def test_flushing_thread__batcher_is_flushed__every_time_flush_interval_time_passes():
    flush_callback = mock.Mock()
    FLUSH_INTERVAL = 0.2
    very_big_batch_size = float("inf")
    batcher = batchers.CreateSpanMessageBatcher(
        flush_callback=flush_callback,
        max_batch_size=very_big_batch_size,
        flush_interval_seconds=FLUSH_INTERVAL,
    )

    spans_messages = fake_message_factory.fake_span_create_message_batch(
        count=2, approximate_span_size=fake_message_factory.ONE_MEGABYTE
    )

    tested = flushing_thread.FlushingThread(batchers=[batcher])

    tested.start()
    batcher.add(spans_messages[0])
    flush_callback.assert_not_called()

    time.sleep(FLUSH_INTERVAL + 0.1)
    # flush interval has passed after batcher was created, batcher is ready to be flushed
    # (0.1 is added because a thread probation interval is 0.1, and it's already made its first check)
    flush_callback.assert_called_once()

    flush_callback.reset_mock()

    batcher.add(spans_messages[1])
    time.sleep(FLUSH_INTERVAL)
    # flush interval has passed after a previous flush, batcher is ready to be flushed again
    flush_callback.assert_called_once()
