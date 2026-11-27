import threading
import time
from unittest import mock

import pytest

from opik import exceptions, synchronization
from opik.message_processing import (
    message_queue,
    streamer_constructors,
    messages,
    queue_consumer,
)

MAX_QUEUE_SIZE = 10


@pytest.fixture
def streamer_with_mock_message_processor(fake_file_upload_manager):
    mock_message_processor = mock.Mock()
    streamer = streamer_constructors.construct_streamer(
        message_processor=mock_message_processor,
        n_consumers=1,
        use_batching=False,
        use_attachment_extraction=False,
        file_uploader=fake_file_upload_manager,
        max_queue_size=MAX_QUEUE_SIZE,
        fallback_replay_manager=mock.Mock(),
    )

    yield streamer, mock_message_processor

    streamer.close(flush=False)


def test_dynamic_rate_limiting__rate_limited__check_queue_messages_are_put_back(
    streamer_with_mock_message_processor,
):
    streamer, mock_message_processor = streamer_with_mock_message_processor

    retry_after = queue_consumer.SLEEP_BETWEEN_LOOP_ITERATIONS * 3
    mock_message_processor.process.side_effect = (
        exceptions.OpikCloudRequestsRateLimited(
            headers={},
            retry_after=retry_after,
        )
    )

    now = time.monotonic()
    messages_number = 5
    for i in range(messages_number):
        streamer.put(messages.BaseMessage())

    # Don't pin the assertion to a fixed sleep matching `retry_after` — that
    # races with the consumer waking from its rate-limit wait at the same
    # instant. Poll the actual signals instead: the rate-limited message has
    # been pushed back (queue_size is at the original count) and the consumer
    # has set a future `next_message_time` (so it's parked in the wait branch
    # and won't pop another message).
    synchronization.until(
        lambda: (
            streamer.queue_size() == messages_number
            and streamer._queue_consumers[0].next_message_time > now
        ),
        max_try_seconds=1.0,
    )
    assert streamer.queue_size() == messages_number
    assert streamer._queue_consumers[0].next_message_time > now

    # Same idea for the drain: poll for queue_size == 0 instead of a fixed
    # sleep tied to retry_after.
    mock_message_processor.process = lambda message: None
    synchronization.until(
        lambda: streamer.queue_size() == 0,
        max_try_seconds=retry_after * 4,
    )
    assert streamer.queue_size() == 0


def test_dynamic_rate_limiting__rate_limited__check_queue_size_is_bounded_by_max_queue_size(
    streamer_with_mock_message_processor,
):
    streamer, mock_message_processor = streamer_with_mock_message_processor

    # to allow a few skipped loop iterations
    retry_after = queue_consumer.SLEEP_BETWEEN_LOOP_ITERATIONS * 3
    mock_message_processor.process.side_effect = (
        exceptions.OpikCloudRequestsRateLimited(
            headers={},
            retry_after=retry_after,
        )
    )

    messages_number = 2 * MAX_QUEUE_SIZE
    for i in range(messages_number):
        streamer.put(messages.BaseMessage())
        time.sleep(queue_consumer.SLEEP_BETWEEN_LOOP_ITERATIONS)

    # sleep for a while to allow queue_consumer to execute a few loop iterations
    time.sleep(retry_after)
    # check that queue size doesn't exceed the maximal allowed size
    assert streamer.queue_size() == MAX_QUEUE_SIZE

    # check that all queue messages are processed when no exception is raised
    mock_message_processor.process = lambda message: None
    time.sleep(retry_after * 2)
    assert streamer.queue_size() == 0


def test_dynamic_rate_limiting__rate_limited__check_streamer_flush_is_blocking_until_all_messages_are_processed(
    streamer_with_mock_message_processor,
):
    streamer, mock_message_processor = streamer_with_mock_message_processor
    retry_after = queue_consumer.SLEEP_BETWEEN_LOOP_ITERATIONS * 3
    max_limit = 5

    counter = {"count": 0}

    def process(message: messages.BaseMessage):
        counter["count"] += 1
        if counter["count"] == max_limit:
            raise exceptions.OpikCloudRequestsRateLimited(
                headers={},
                retry_after=retry_after,
            )

    mock_message_processor.process = process

    for i in range(max_limit):
        streamer.put(messages.BaseMessage())

    streamer.flush(timeout=None)

    # we expect max_limit calls before retry plus one call after waiting for the retry
    assert counter["count"] == max_limit + 1


def test_dynamic_rate_limiting__rate_limited__pause_is_shared_by_all_consumers():
    retry_after = 0.4
    queue = message_queue.MessageQueue(max_length=None)
    processor = mock.Mock()

    call_times = []
    call_lock = threading.Lock()
    state = {"first": True}

    def process(message):
        # Every send is an HTTP round trip, as in the issue reproduction.
        time.sleep(0.01)
        with call_lock:
            call_times.append(time.monotonic())
            is_first = state["first"]
            state["first"] = False
        if is_first:
            raise exceptions.OpikCloudRequestsRateLimited(
                headers={}, retry_after=retry_after
            )

    processor.process.side_effect = process

    consumers = [
        queue_consumer.QueueConsumer(queue, processor, name=f"rate-limit-c{i}")
        for i in range(4)
    ]
    for consumer in consumers:
        consumer.start()

    try:
        for _ in range(50):
            queue.put(messages.BaseMessage())

        assert synchronization.until(
            lambda: queue.get_delivery_paused_until() > time.monotonic(),
            max_try_seconds=1.0,
        )
        paused_until = queue.get_delivery_paused_until()

        # All consumers share the queue-wide deadline, not a per-thread one.
        assert all(
            consumer._message_queue.get_delivery_paused_until() == paused_until
            for consumer in consumers
        )

        # The deferred message must not be popped and re-enqueued in a tight
        # loop, and the rest of the queue must not be sent during the window.
        time.sleep(retry_after * 0.5)
        with call_lock:
            early_calls = [at for at in call_times if at < paused_until]
        # A message already popped when the 429 lands can still be in flight;
        # at most one wave of those can slip through, never the rest of the queue.
        assert len(early_calls) <= 2 * len(consumers) + 2, early_calls

        pending = list(queue._deque)
        assert max((m.delivery_attempts for m in pending), default=0) < 10, [
            m.delivery_attempts for m in pending
        ]

        # Once the Retry-After window expires the queue drains normally.
        processor.process.side_effect = None
        assert synchronization.until(
            lambda: queue.all_tasks_done(), max_try_seconds=retry_after * 10
        )
    finally:
        for consumer in consumers:
            consumer.close()
        for consumer in consumers:
            consumer.join(timeout=2.0)
