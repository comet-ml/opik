from unittest import mock
import time

import pytest

from opik import config
from opik.message_processing import messages
from opik.message_processing.batching import batch_manager
from opik.message_processing.batching import batch_manager_constuctors
from opik.message_processing.batching import batchers
from opik.message_processing.batching import sequence_splitter

from ....testlib import fake_message_factory

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


def test_batch_manager__flush_then_stop__accumulated_data_is_flushed_and_thread_is_stopped():
    collected_messages = []

    def flush_callback(message: messages.BaseMessage):
        collected_messages.append(message)

    span_messages_batch = fake_message_factory.fake_span_create_message_batch(
        count=10, approximate_span_size=fake_message_factory.ONE_MEGABYTE
    )

    example_span_batcher = batchers.CreateSpanMessageBatcher(
        flush_callback=flush_callback,
        max_batch_size=42,
        flush_interval_seconds=0.1,
        batch_memory_limit_mb=5,
    )
    tested = batch_manager.BatchManager(
        {messages.CreateSpanMessage: example_span_batcher}
    )

    tested.start()
    time.sleep(0.1)

    for span_message in span_messages_batch:
        tested.process_message(span_message)

    # BatchManager.stop() is a pure signal now — callers must flush first if
    # they want pending batches pushed downstream.
    tested.flush()
    tested.stop()

    assert len(collected_messages) >= 2


@pytest.mark.parametrize(
    "batch_message_class, score_message_class",
    [
        (messages.AddSpanFeedbackScoresBatchMessage, messages.FeedbackScoreMessage),
        (messages.AddTraceFeedbackScoresBatchMessage, messages.FeedbackScoreMessage),
        (
            messages.AddThreadsFeedbackScoresBatchMessage,
            messages.ThreadsFeedbackScoreMessage,
        ),
    ],
)
def test_create_batch_manager__feedback_scores_over_the_producer_limit__split_to_that_limit(
    batch_message_class, score_message_class
):
    queue = mock.Mock()
    tested = batch_manager_constuctors.create_batch_manager(queue)

    scores = [
        score_message_class(
            id=f"id-{i}",
            project_name="project",
            name="relevance",
            value=1.0,
            source="sdk",
            reason="r" * fake_message_factory.ONE_MEGABYTE,
        )
        for i in range(7)
    ]
    for score in scores:
        tested.process_message(batch_message_class(batch=[score]))
    tested.flush()

    emitted = [call.args[0] for call in queue.put.call_args_list]
    assert len(emitted) > 1
    for message in emitted:
        assert (
            sequence_splitter.get_payload_size_MB(message.batch)
            <= config.MAX_BATCH_SIZE_MB
        )
    assert [score for message in emitted for score in message.batch] == scores
