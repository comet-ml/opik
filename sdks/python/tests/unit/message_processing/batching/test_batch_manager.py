from unittest import mock
import time

from opik.message_processing import messages
from opik.message_processing.batching import batch_manager
from opik.message_processing.batching import batchers

NOT_USED = None


def test_batch_manager__messages_processing_methods():
    integers_batcher = mock.Mock()
    strings_batcher = mock.Mock()
    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)

    assert tested.message_supports_batching("a-string")
    assert tested.message_supports_batching(42)
    assert not tested.message_supports_batching(float(42.5))

    tested.process_message(42)
    integers_batcher.add.assert_called_once_with(42)

    tested.process_message("a-string")
    strings_batcher.add.assert_called_once_with("a-string")


def test_batch_manager__all_batchers_are_empty__batch_manager_is_empty():
    integers_batcher = mock.Mock()
    integers_batcher.is_empty.return_value = True
    strings_batcher = mock.Mock()
    strings_batcher.is_empty.return_value = True

    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)

    assert tested.is_empty()
    strings_batcher.is_empty.assert_called_once()
    integers_batcher.is_empty.assert_called_once()


def test_batch_manager__at_least_one_batcher_is_not_empty__batch_manager_is_not_empty():
    integers_batcher = mock.Mock()
    integers_batcher.is_empty.return_value = True
    strings_batcher = mock.Mock()
    strings_batcher.is_empty.return_value = False

    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)

    assert not tested.is_empty()
    strings_batcher.is_empty.assert_called_once()
    integers_batcher.is_empty.assert_called_once()


def test_batch_manager__flush_is_called__all_batchers_are_flushed():
    integers_batcher = mock.Mock()
    strings_batcher = mock.Mock()

    MESSAGE_BATCHERS = {
        int: integers_batcher,
        str: strings_batcher,
    }

    tested = batch_manager.BatchManager(MESSAGE_BATCHERS)
    tested.flush()
    integers_batcher.flush.assert_called_once()
    strings_batcher.flush.assert_called_once()


def test_batch_manager__start_and_stop_were_called__accumulated_data_is_flushed():
    flush_callback = mock.Mock()

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
        model=NOT_USED,
        provider=NOT_USED,
        error_info=NOT_USED,
        total_cost=NOT_USED,
    )

    example_span_batcher = batchers.CreateSpanMessageBatcher(
        flush_callback=flush_callback, max_batch_size=42, flush_interval_seconds=0.1
    )
    tested = batch_manager.BatchManager(
        {messages.CreateSpanMessage: example_span_batcher}
    )

    tested.start()
    time.sleep(0.1)
    flush_callback.assert_not_called()
    tested.process_message(CREATE_SPAN_MESSAGE)
    tested.stop()
    flush_callback.assert_called_once_with(
        messages.CreateSpansBatchMessage(batch=[CREATE_SPAN_MESSAGE])
    )
