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


def test_create_span_message_batcher__add_duplicated_span__previous_span_is_removed_from_batcher():
    """
    Tests the handling of duplicate spans in a span message batcher, specifically focusing on ensuring
    that newer ones replace older duplicated spans.
    """
    MAX_BATCH_SIZE = 5

    batches = []

    def flush_callback(batch):
        batches.append(batch)

    batcher = batchers.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )
    assert batcher.is_empty()

    span_messages = []
    span_messages += fake_message_factory.fake_span_create_message_batch(
        count=1,
        approximate_span_size=fake_message_factory.ONE_KILOBYTE,
        has_ended=False,
    )
    span_messages += fake_message_factory.fake_span_create_message_batch(
        count=1, approximate_span_size=fake_message_factory.ONE_KILOBYTE, has_ended=True
    )

    batcher.add(span_messages[0])
    assert batcher.size() == 1

    # modify the second span to be considered the same as the first one
    span_messages[1].span_id = span_messages[0].span_id
    batcher.add(span_messages[1])

    # assert that the first span is removed from the batcher
    assert batcher.size() == 1

    # check that the second message is in the batch
    batcher.flush()
    assert len(batches) == 1
    assert len(batches[0].batch) == 1
    assert batches[0].batch[0].id == span_messages[1].span_id


def test_create_span_message_batcher__add_duplicated_span__previous_span_is_not_removed_from_batcher__not_ended_span():
    """
    Tests the behavior of adding a duplicate span to a span message batcher and ensures
    that the previous span is not removed when adding a duplicate span if both are not ended.
    """
    MAX_BATCH_SIZE = 5

    batches = []

    def flush_callback(batch):
        batches.append(batch)

    batcher = batchers.CreateSpanMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )
    assert batcher.is_empty()

    span_messages = fake_message_factory.fake_span_create_message_batch(
        count=2,
        approximate_span_size=fake_message_factory.ONE_KILOBYTE,
        has_ended=False,
    )

    batcher.add(span_messages[0])
    assert batcher.size() == 1

    # modify the second span to be considered the same as the first one
    span_messages[1].span_id = span_messages[0].span_id
    batcher.add(span_messages[1])

    # assert that both spans are in the batcher
    assert batcher.size() == 2
