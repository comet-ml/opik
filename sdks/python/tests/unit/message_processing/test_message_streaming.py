import pytest
import mock
from opik.message_processing import streamer_constructors
from opik.message_processing import messages

NOT_USED = None


@pytest.fixture
def batched_streamer_and_mock_message_processor():
    try:
        mock_message_processor = mock.Mock()
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
        )

        yield tested, mock_message_processor
    finally:
        tested.close(timeout=5)


def test_streamer__happyflow(batched_streamer_and_mock_message_processor):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    tested.put("message-1")
    tested.put("message-2")
    tested.flush(timeout=0.1)

    mock_message_processor.process.assert_has_calls(
        [mock.call("message-1"), mock.call("message-2")]
    )


def test_streamer__batching_disabled__messages_that_support_batching_are_processed_independently():
    mock_message_processor = mock.Mock()
    try:
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=False,
        )

        CREATE_SPAN_MESSAGE = messages.CreateSpanMessage(
            span_id=NOT_USED,
            trace_id=NOT_USED,
            parent_span_id=NOT_USED,
            project_name=NOT_USED,
            start_time=NOT_USED,
            end_time=NOT_USED,
            name=NOT_USED,
            input=NOT_USED,
            output=NOT_USED,
            metadata=NOT_USED,
            tags=NOT_USED,
            type=NOT_USED,
            usage=NOT_USED,
        )

        tested.put(CREATE_SPAN_MESSAGE)
        tested.put(CREATE_SPAN_MESSAGE)
        tested.put(CREATE_SPAN_MESSAGE)
        tested.flush(0.1)

        mock_message_processor.process.assert_has_calls(
            [
                mock.call(CREATE_SPAN_MESSAGE),
                mock.call(CREATE_SPAN_MESSAGE),
                mock.call(CREATE_SPAN_MESSAGE),
            ]
        )
    finally:
        tested.close(timeout=1)


def test_streamer__batching_enabled__messages_that_support_batching_are_processed_in_batch():
    mock_message_processor = mock.Mock()
    try:
        tested = streamer_constructors.construct_streamer(
            message_processor=mock_message_processor,
            n_consumers=1,
            use_batching=True,
        )

        CREATE_SPAN_MESSAGE = messages.CreateSpanMessage(
            span_id=NOT_USED,
            trace_id=NOT_USED,
            parent_span_id=NOT_USED,
            project_name=NOT_USED,
            start_time=NOT_USED,
            end_time=NOT_USED,
            name=NOT_USED,
            input=NOT_USED,
            output=NOT_USED,
            metadata=NOT_USED,
            tags=NOT_USED,
            type=NOT_USED,
            usage=NOT_USED,
        )

        tested.put(CREATE_SPAN_MESSAGE)
        tested.put(CREATE_SPAN_MESSAGE)
        tested.put(CREATE_SPAN_MESSAGE)
        tested.flush(1.1)

        mock_message_processor.process.assert_called_once_with(
            messages.CreateSpansBatchMessage(batch=[CREATE_SPAN_MESSAGE] * 3)
        )
    finally:
        tested.close(timeout=1)
