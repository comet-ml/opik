from unittest.mock import sentinel

import pytest
from unittest import mock
from opik.message_processing import streamer_constructors
from opik.message_processing import messages

NOT_USED = sentinel.NOT_USED


def create_span_message():
    return messages.CreateSpanMessage(
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
        model=NOT_USED,
        provider=NOT_USED,
        error_info=NOT_USED,
        total_cost=NOT_USED,
    )


def create_trace_message():
    return messages.CreateTraceMessage(
        trace_id=NOT_USED,
        project_name=NOT_USED,
        start_time=NOT_USED,
        end_time=NOT_USED,
        name=NOT_USED,
        input=NOT_USED,
        output=NOT_USED,
        metadata=NOT_USED,
        tags=NOT_USED,
        error_info=NOT_USED,
        thread_id=NOT_USED,
    )


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
        [mock.call(test_messages[0]), mock.call(test_messages[1])]
    )


@pytest.mark.parametrize(
    "obj",
    [
        create_span_message(),
        create_trace_message(),
    ],
)
def test_streamer__batching_disabled__messages_that_support_batching_are_processed_independently(
    obj,
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

        CREATE_MESSAGE = obj

        tested.put(CREATE_MESSAGE)
        tested.put(CREATE_MESSAGE)
        tested.put(CREATE_MESSAGE)
        assert tested.flush(0.1) is True

        mock_message_processor.process.assert_has_calls(
            [
                mock.call(CREATE_MESSAGE),
                mock.call(CREATE_MESSAGE),
                mock.call(CREATE_MESSAGE),
            ]
        )
    finally:
        if tested is not None:
            tested.close(timeout=1)


def test_streamer__span__batching_enabled__messages_that_support_batching_are_processed_in_batch(
    batched_streamer_and_mock_message_processor,
):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    CREATE_SPAN_MESSAGE = create_span_message()

    tested.put(CREATE_SPAN_MESSAGE)
    tested.put(CREATE_SPAN_MESSAGE)
    tested.put(CREATE_SPAN_MESSAGE)
    assert tested.flush(1.1) is True

    mock_message_processor.process.assert_called_once_with(
        messages.CreateSpansBatchMessage(batch=[CREATE_SPAN_MESSAGE] * 3)
    )


def test_streamer__trace__batching_enabled__messages_that_support_batching_are_processed_in_batch(
    batched_streamer_and_mock_message_processor,
):
    tested, mock_message_processor = batched_streamer_and_mock_message_processor

    CREATE_TRACE_MESSAGE = create_trace_message()

    tested.put(CREATE_TRACE_MESSAGE)
    tested.put(CREATE_TRACE_MESSAGE)
    tested.put(CREATE_TRACE_MESSAGE)
    assert tested.flush(1.1) is True

    mock_message_processor.process.assert_called_once_with(
        messages.CreateTraceBatchMessage(batch=[CREATE_TRACE_MESSAGE] * 3)
    )
