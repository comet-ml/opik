from unittest import mock

from opik.message_processing import message_processors

from . import common


def test_process__CreateTraceBatchMessage__mini_batches_created_and_pushed_back_into_callback():
    batch_message = common.create_fake_trace_batch(
        count=100, approximate_trace_size=common.ONE_MEGABYTE
    )

    assert len(batch_message.batch) == 100

    processor = message_processors.OpikMessageProcessor(
        rest_client=mock.Mock(), batch_memory_limit_mb=10
    )

    accumulator = []

    def push_back_callback(message):
        accumulator.append(message)

    processor.process(batch_message, push_back_callback)
    size = len(accumulator)

    assert size >= 10


def test_process__CreateSpanBatchMessage__mini_batches_created_and_pushed_back_into_callback():
    batch_message = common.create_fake_span_batch(
        count=100, approximate_span_size=common.ONE_MEGABYTE
    )

    assert len(batch_message.batch) == 100

    processor = message_processors.OpikMessageProcessor(
        rest_client=mock.Mock(), batch_memory_limit_mb=10
    )

    accumulator = []

    def push_back_callback(message):
        accumulator.append(message)

    processor.process(batch_message, push_back_callback)
    size = len(accumulator)

    assert size >= 10
