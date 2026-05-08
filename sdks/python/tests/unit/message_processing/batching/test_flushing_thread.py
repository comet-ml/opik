import threading
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

    tested = flushing_thread.FlushingThread(batchers=[batcher], lock=threading.RLock())

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


def test_flushing_thread__concurrent_adds_during_flush__no_messages_dropped():
    """Regression test for OPIK-6444.

    The FlushingThread used to call `batcher.flush()` without holding the
    BatchManager lock, while `BatchManager.process_message` holds it for
    `add()`. Messages appended during the post-iteration tail of
    `_create_batches_from_accumulated_messages()` (notably during
    `sequence_splitter.split_into_batches()`) were not in the returned
    batches, but were wiped by `self._accumulated_messages = []`.
    """
    delivered_trace_ids = set()
    delivered_lock = threading.Lock()

    def flush_callback(batch_message):
        with delivered_lock:
            for trace in batch_message.batch:
                delivered_trace_ids.add(trace.id)

    batcher = batchers.CreateTraceMessageBatcher(
        flush_callback=flush_callback,
        max_batch_size=10_000,
        flush_interval_seconds=0.005,
    )

    shared_lock = threading.RLock()
    tested = flushing_thread.FlushingThread(
        batchers=[batcher], lock=shared_lock, probe_interval_seconds=0.001
    )
    tested.start()

    WRITERS = 4
    ADDS_PER_WRITER = 100
    PAYLOAD_SIZE = 50 * fake_message_factory.ONE_KILOBYTE
    added_trace_ids = []
    added_lock = threading.Lock()

    def writer():
        trace_messages = fake_message_factory.fake_create_trace_message_batch(
            count=ADDS_PER_WRITER,
            approximate_trace_size=PAYLOAD_SIZE,
            has_ended=True,
        )
        for message in trace_messages:
            with shared_lock:
                batcher.add(message)
            with added_lock:
                added_trace_ids.append(message.trace_id)

    writer_threads = [threading.Thread(target=writer) for _ in range(WRITERS)]
    for writer_thread in writer_threads:
        writer_thread.start()
    for writer_thread in writer_threads:
        writer_thread.join()

    tested.close()
    with shared_lock:
        batcher.flush()

    assert set(added_trace_ids) == delivered_trace_ids, (
        f"dropped {len(set(added_trace_ids) - delivered_trace_ids)} of "
        f"{len(added_trace_ids)} trace messages"
    )


def test_flushing_thread__exception_in_flush__thread_keeps_running():
    """Regression test for OPIK-6444.

    `FlushingThread.run` had no try/except, so any exception in `flush()`
    silently killed the daemon thread, after which periodic flushing stopped.
    """
    flush_callback = mock.Mock()
    batcher = batchers.CreateSpanMessageBatcher(
        flush_callback=flush_callback,
        max_batch_size=float("inf"),
        flush_interval_seconds=0.05,
    )

    real_flush = batcher.flush
    call_counter = {"n": 0}

    def flaky_flush():
        call_counter["n"] += 1
        if call_counter["n"] == 1:
            raise RuntimeError("simulated transient flush failure")
        real_flush()

    batcher.flush = flaky_flush  # type: ignore[method-assign]

    spans = fake_message_factory.fake_span_create_message_batch(
        count=2, approximate_span_size=fake_message_factory.ONE_KILOBYTE
    )

    tested = flushing_thread.FlushingThread(
        batchers=[batcher], lock=threading.RLock(), probe_interval_seconds=0.01
    )
    tested.start()

    batcher.add(spans[0])
    time.sleep(0.2)  # let the first (failing) flush fire

    batcher.add(spans[1])
    time.sleep(0.2)  # second flush should still happen after the failure

    tested.close()

    assert call_counter["n"] >= 2, "FlushingThread stopped after the first failure"
    assert flush_callback.called, "no batch was delivered after the recovery"
