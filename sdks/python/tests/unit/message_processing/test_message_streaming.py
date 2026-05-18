import threading
from unittest import mock
from unittest.mock import sentinel

import pytest

from opik.message_processing import messages
from opik.message_processing import streamer_constructors
from ...testlib import fake_message_factory

NOT_USED = sentinel.NOT_USED


@pytest.fixture
def batched_streamer_and_mock_message_processor(
    fake_file_upload_manager, fake_replay_manager
):
    tested = None
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=fake_replay_manager,
        )

        yield tested, mock_message_processor
    finally:
        if tested is not None:
            tested.close(flush=False)


def test_streamer__drain_to_processors__waits_until_slow_process_returns(
    fake_file_upload_manager, fake_replay_manager
):
    # Regression: previously `_all_done()` checked `workers_idling and queue.empty`
    # — both could flip True in the gap between the consumer popping a message
    # and entering `message_processor.process(...)`. With the unfinished-task
    # counter, drain_to_processors must block until `process` actually returns.
    process_started = threading.Event()
    release_process = threading.Event()
    process_returned = threading.Event()

    def slow_process(message):
        process_started.set()
        release_process.wait(timeout=5.0)
        process_returned.set()

    mock_message_processor = mock.Mock()
    mock_message_processor.process.side_effect = slow_process

    tested = streamer_constructors.construct_streamer(
        message_processor=mock_message_processor,
        n_consumers=1,
        use_batching=False,
        use_attachment_extraction=False,
        file_uploader=fake_file_upload_manager,
        max_queue_size=None,
        fallback_replay_manager=fake_replay_manager,
    )
    try:
        tested.put(messages.BaseMessage())

        # Wait until the consumer has popped the message and is inside
        # `process` — i.e. queue is empty but a task is in flight.
        assert process_started.wait(timeout=2.0) is True

        # A very short drain budget should NOT report success: process is
        # still running, so the queue is not quiescent.
        assert tested.drain_to_processors(timeout=0.1) is False
        assert process_returned.is_set() is False

        # Release the processor; the next drain must wait for it to finish
        # and then return True.
        release_process.set()
        assert tested.drain_to_processors(timeout=2.0) is True
        assert process_returned.is_set() is True
    finally:
        tested.close(flush=False)


def test_streamer__happy_flow(batched_streamer_and_mock_message_processor):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    test_messages = [messages.BaseMessage(), messages.BaseMessage]

    tested.put(test_messages[0])
    tested.put(test_messages[1])
    assert tested.flush(timeout=0.01) is True

    mock_message_processor.process.assert_has_calls(
        [
            mock.call(test_messages[0]),
            mock.call(test_messages[1]),
        ]
    )


@pytest.mark.parametrize(
    "objects",
    [
        fake_message_factory.fake_create_trace_message_batch(count=3),
        fake_message_factory.fake_create_trace_message_batch(count=3),
    ],
)
def test_streamer__batching_disabled__messages_that_support_batching_are_processed_independently(
    objects, fake_file_upload_manager
):
    mock_message_processor = mock.Mock()
    tested = None
    try:
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=False,
            use_attachment_extraction=False,
            file_uploader=fake_file_upload_manager,
            max_queue_size=None,
            fallback_replay_manager=mock.Mock(),
        )

        for obj in objects:
            tested.put(obj)
        assert tested.flush(0.1) is True

        mock_message_processor.process.assert_has_calls(
            [
                mock.call(objects[0]),
                mock.call(objects[1]),
                mock.call(objects[2]),
            ]
        )
    finally:
        if tested is not None:
            tested.close(flush=False)


def test_streamer__span__batching_enabled__messages_that_support_batching_are_processed_in_batch(
    batched_streamer_and_mock_message_processor,
):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    create_span_messages = fake_message_factory.fake_create_trace_message_batch(count=3)

    for message in create_span_messages:
        tested.put(message)

    assert tested.flush(1.1) is True

    mock_message_processor.process.assert_called_once()


def test_streamer__trace__batching_enabled__messages_that_support_batching_are_processed_in_batch(
    batched_streamer_and_mock_message_processor,
):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    create_trace_messages = fake_message_factory.fake_create_trace_message_batch(3)
    for message in create_trace_messages:
        tested.put(message)

    assert tested.flush(1.1) is True

    mock_message_processor.process.assert_called_once()
