from unittest.mock import sentinel

import pytest
from unittest import mock
from opik.message_processing import streamer_constructors
from opik.message_processing import messages

from ...testlib import fake_message_factory

NOT_USED = sentinel.NOT_USED


@pytest.fixture
def batched_streamer_and_mock_message_processor():
    tested = None
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
            file_upload_manager=mock.Mock(),
            max_queue_size=None,
        )

        yield tested, mock_message_processor
    finally:
        if tested is not None:
            tested.close(timeout=5)


def test_streamer__happy_flow(batched_streamer_and_mock_message_processor):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    test_messages = [messages.BaseMessage(), messages.BaseMessage]

    tested.put(test_messages[0])
    tested.put(test_messages[1])
    assert tested.flush(timeout=0.0001) is True

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
    objects,
):
    mock_message_processor = mock.Mock()
    tested = None
    try:
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=False,
            file_upload_manager=mock.Mock(),
            max_queue_size=None,
        )

        for object in objects:
            tested.put(object)
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
            tested.close(timeout=1)


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
