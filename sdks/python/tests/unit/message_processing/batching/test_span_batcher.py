from unittest import mock
import time

import pytest

from opik.message_processing.batching import batchers
from opik.message_processing import messages
from ....testlib import fake_message_factory

NOT_USED = mock.sentinel.NOT_USED


def test_create_span_message_batcher__exactly_max_batch_size_reached__batch_is_flushed():
    collected_messages = []

    def flush_callback(message: messages.BaseMessage):
        collected_messages.append(message)

    MAX_BATCH_SIZE = 5

    batcher = batchers.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
        batch_memory_limit_mb=10,
    )

    assert batcher.is_empty()
    span_messages = fake_message_factory.fake_span_create_message_batch(
        count=MAX_BATCH_SIZE, approximate_span_size=fake_message_factory.ONE_MEGABYTE
    )

    for span_message in span_messages:
        batcher.add(span_message)
    assert batcher.is_empty()

    assert len(collected_messages) == 1


def test_create_span_message_batcher__more_than_max_batch_size_items_added__one_batch_flushed__some_data_remains_in_batcher():
    collected_messages = []

    def flush_callback(message: messages.BaseMessage):
        collected_messages.append(message)

    MAX_BATCH_SIZE = 5

    batcher = batchers.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
        batch_memory_limit_mb=10,
    )

    assert batcher.is_empty()
    span_messages = fake_message_factory.fake_span_create_message_batch(
        count=MAX_BATCH_SIZE + 2,
        approximate_span_size=fake_message_factory.ONE_MEGABYTE,
    )

    for span_message in span_messages:
        batcher.add(span_message)

    assert not batcher.is_empty()
    assert len(collected_messages) == 1

    batcher.flush()
    assert len(collected_messages) == 2


def test_create_span_message_batcher__split_message_into_batches__size_limit_reached():
    collected_messages = []

    def flush_callback(message: messages.BaseMessage):
        collected_messages.append(message)

    MAX_BATCH_SIZE = 5

    batcher = batchers.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
        batch_memory_limit_mb=3,
    )

    assert batcher.is_empty()
    span_messages = fake_message_factory.fake_span_create_message_batch(
        count=2 * 2, approximate_span_size=fake_message_factory.ONE_MEGABYTE
    )

    for span_message in span_messages:
        batcher.add(span_message)

    assert not batcher.is_empty()
    batcher.flush()

    # we created 4 messages about 1 MB each, and batcher is limited to 3 MB - thus we expect 2 batches
    assert len(collected_messages) == 2


@pytest.mark.parametrize(
    "message_batcher_class",
    [
        batchers.CreateSpanMessageBatcher,
        batchers.CreateTraceMessageBatcher,
    ],
)
def test_create_message_batcher__batcher_doesnt_have_items__flush_is_called__flush_callback_NOT_called(
    message_batcher_class,
):
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = message_batcher_class(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
        batch_memory_limit_mb=10,
    )

    assert batcher.is_empty()
    batcher.flush()
    flush_callback.assert_not_called()


@pytest.mark.parametrize(
    "message_batcher_class",
    [
        batchers.CreateSpanMessageBatcher,
        batchers.CreateTraceMessageBatcher,
    ],
)
def test_create_message_batcher__ready_to_flush_returns_True__is_flush_interval_passed(
    message_batcher_class,
):
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5
    FLUSH_INTERVAL = 0.1

    batcher = message_batcher_class(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=FLUSH_INTERVAL,
        batch_memory_limit_mb=10,
    )
    assert not batcher.is_ready_to_flush()
    time.sleep(0.1)
    assert batcher.is_ready_to_flush()
    batcher.flush()
    assert not batcher.is_ready_to_flush()
    time.sleep(0.1)
    assert batcher.is_ready_to_flush()
