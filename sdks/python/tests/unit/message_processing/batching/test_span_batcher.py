import mock
import time

from opik.message_processing.batching import create_span_message_batcher
from opik.message_processing import messages

NOT_USED = None


def test_create_span_message_batcher__exactly_max_batch_size_reached__batch_is_flushed():
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = create_span_message_batcher.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    span_messages = [
        1,
        2,
        3,
        4,
        5,
    ]  # batcher doesn't care about the content, it doesn't work

    for span_message in span_messages:
        batcher.add(span_message)
    assert batcher.is_empty()

    flush_callback.assert_called_once_with(
        messages.CreateSpansBatchMessage(batch=[1, 2, 3, 4, 5])
    )


def test_create_span_message_batcher__more_than_max_batch_size_items_added__one_batch_flushed__some_data_remains_in_batcher():
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = create_span_message_batcher.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    span_messages = [
        1,
        2,
        3,
        4,
        5,
        6,
        7,
    ]  # batcher doesn't care about the content, it doesn't work

    for span_message in span_messages:
        batcher.add(span_message)

    assert not batcher.is_empty()
    flush_callback.assert_called_once_with(
        messages.CreateSpansBatchMessage(batch=[1, 2, 3, 4, 5])
    )
    flush_callback.reset_mock()

    batcher.flush()
    flush_callback.assert_called_once_with(
        messages.CreateSpansBatchMessage(batch=[6, 7])
    )


def test_create_span_message_batcher__batcher_doesnt_have_items__flush_is_called__flush_callback_NOT_called():
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = create_span_message_batcher.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    batcher.flush()
    flush_callback.assert_not_called()


def test_create_span_message_batcher__ready_to_flush_returns_True__is_flush_interval_passed():
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5
    FLUSH_INTERVAL = 0.1

    batcher = create_span_message_batcher.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=FLUSH_INTERVAL,
    )
    assert not batcher.is_ready_to_flush()
    time.sleep(0.1)
    assert batcher.is_ready_to_flush()
    batcher.flush()
    assert not batcher.is_ready_to_flush()
    time.sleep(0.1)
    assert batcher.is_ready_to_flush()
