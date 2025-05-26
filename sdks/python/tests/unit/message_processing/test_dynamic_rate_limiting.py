import time
from unittest import mock

import pytest

from opik import exceptions
from opik.message_processing import streamer_constructors, messages, queue_consumer

MAX_QUEUE_SIZE = 10


@pytest.fixture
def steamer_with_mock_message_processor():
    mock_message_processor = mock.Mock()
    streamer = streamer_constructors.construct_streamer(
        message_processor=mock_message_processor,
        n_consumers=1,
        use_batching=False,
        file_upload_manager=mock.Mock(),
        max_queue_size=MAX_QUEUE_SIZE,
    )

    yield streamer, mock_message_processor

    streamer.close(None)


def test_dynamic_rate_limiting__check_queue_messages_are_put_back(
    steamer_with_mock_message_processor,
):
    streamer, mock_message_processor = steamer_with_mock_message_processor

    # to allow a few skipped loop iterations
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

    # sleep for a while to allow queue_consumer to execute a few loop iterations
    time.sleep(retry_after)
    # check that messages were put back into the message queue for retry
    assert streamer.queue_size() == messages_number
    assert streamer._queue_consumers[0].next_message_time > now

    # check that all queue messages are processed when no exception is raised
    mock_message_processor.process = lambda message: None
    time.sleep(retry_after * 2)
    assert streamer.queue_size() == 0


def test_dynamic_rate_limiting__check_queue_size_is_bounded_by_max_queue_size(
    steamer_with_mock_message_processor,
):
    streamer, mock_message_processor = steamer_with_mock_message_processor

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
