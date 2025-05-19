from unittest import mock

from opik.message_processing import message_processors

from . import common


def test_process_create_trace_batch_message():
    batch_message = common.create_dummy_trace_batch(100)

    assert len(batch_message.batch) == 100

    processor = message_processors.MessageSender(
        rest_client=mock.Mock(), batch_memory_limit_mb=10
    )

    accumulator = []

    def push_back_callback(message):
        accumulator.append(message)

    processor.process(batch_message, push_back_callback)
    size = len(accumulator)

    assert size >= 10


def test_process_create_span_batch_message():
    batch_message = common.create_dummy_span_batch(100)

    assert len(batch_message.batch) == 100

    processor = message_processors.MessageSender(
        rest_client=mock.Mock(), batch_memory_limit_mb=10
    )

    accumulator = []

    def push_back_callback(message):
        accumulator.append(message)

    processor.process(batch_message, push_back_callback)
    size = len(accumulator)

    assert size >= 10
