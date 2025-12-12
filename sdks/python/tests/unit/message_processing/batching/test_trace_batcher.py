from unittest import mock

from opik.message_processing import messages
from opik.message_processing.batching import batchers

from ....testlib import fake_message_factory

NOT_USED = mock.sentinel.NOT_USED


def test_create_trace_message_batcher__split_message_into_batches__size_limit_reached():
    collected_messages = []

    def flush_callback(message: messages.BaseMessage):
        collected_messages.append(message)

    MAX_BATCH_SIZE = 5

    batcher = batchers.CreateTraceMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
        batch_memory_limit_mb=3,
    )

    assert batcher.is_empty()
    trace_messages = fake_message_factory.fake_create_trace_message_batch(
        count=2 * 2, approximate_trace_size=fake_message_factory.ONE_MEGABYTE
    )

    for trace_message in trace_messages:
        batcher.add(trace_message)

    assert not batcher.is_empty()
    batcher.flush()

    # we created 4 messages about 1 MB each, and batcher is limited to 3 MB - thus we expect 2 batches
    assert len(collected_messages) == 2


def test_create_trace_message_batcher__add_duplicated_trace__previous_trace_is_removed_from_batcher():
    """
    Tests the handling of duplicate traces in a trace message batcher, specifically focusing on ensuring
    that newer ones replace older duplicated traces.
    """
    MAX_BATCH_SIZE = 5

    batches = []

    def flush_callback(batch):
        batches.append(batch)

    batcher = batchers.CreateTraceMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()

    trace_messages = []
    trace_messages += fake_message_factory.fake_create_trace_message_batch(
        count=1,
        approximate_trace_size=fake_message_factory.ONE_KILOBYTE,
        has_ended=False,
    )
    trace_messages += fake_message_factory.fake_create_trace_message_batch(
        count=1,
        approximate_trace_size=fake_message_factory.ONE_KILOBYTE,
        has_ended=True,
    )

    batcher.add(trace_messages[0])
    assert batcher.size() == 1

    # modify the second trace to be considered the same as the first one
    trace_messages[1].trace_id = trace_messages[0].trace_id

    # add the second trace to the batcher and check that the first one is removed from the batcher
    batcher.add(trace_messages[1])
    assert batcher.size() == 1

    batcher.flush()
    assert len(batches) == 1
    assert len(batches[0].batch) == 1
    assert batches[0].batch[0].id == trace_messages[1].trace_id


def test_create_trace_message_batcher__add_duplicated_trace__previous_trace_is_not_removed_from_batcher__not_ended_trace():
    """
    Tests the behavior of adding a duplicate trace to a trace message batcher and ensures
    that the previous trace is not removed when adding a duplicate trace if both are not ended.
    """
    MAX_BATCH_SIZE = 5

    batches = []

    def flush_callback(batch):
        batches.append(batch)

    batcher = batchers.CreateTraceMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()

    trace_messages = fake_message_factory.fake_create_trace_message_batch(
        count=2,
        approximate_trace_size=fake_message_factory.ONE_KILOBYTE,
        has_ended=False,
    )

    batcher.add(trace_messages[0])
    assert batcher.size() == 1

    # modify the second trace to be considered the same as the first one
    trace_messages[1].trace_id = trace_messages[0].trace_id

    # add the second trace to the batcher and check that the first one is removed from the batcher
    batcher.add(trace_messages[1])
    assert batcher.size() == 2
