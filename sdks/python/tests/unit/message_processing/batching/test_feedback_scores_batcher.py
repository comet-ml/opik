from unittest import mock
import time

import pytest

from opik.message_processing.batching import batchers, sequence_splitter
from opik.message_processing import messages

from ....testlib import fake_message_factory

NOT_USED = None


@pytest.mark.parametrize(
    "message_batcher_class, batch_message_class",
    [
        (
            batchers.AddSpanFeedbackScoresBatchMessageBatcher,
            messages.AddSpanFeedbackScoresBatchMessage,
        ),
        (
            batchers.AddTraceFeedbackScoresBatchMessageBatcher,
            messages.AddTraceFeedbackScoresBatchMessage,
        ),
        (
            batchers.AddThreadsFeedbackScoresBatchMessageBatcher,
            messages.AddThreadsFeedbackScoresBatchMessage,
        ),
    ],
)
def test_add_feedback_scores_batch_message_batcher__exactly_max_batch_size_reached__batch_is_flushed(
    message_batcher_class,
    batch_message_class,
):
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = message_batcher_class(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    add_feedback_score_batch_messages = [
        messages.AddSpanFeedbackScoresBatchMessage(batch=[1, 2]),
        messages.AddSpanFeedbackScoresBatchMessage(batch=[3, 4, 5]),
    ]  # batcher doesn't care about the content

    for feedback_scores_batch in add_feedback_score_batch_messages:
        batcher.add(feedback_scores_batch)
    assert batcher.is_empty()

    flush_callback.assert_called_once_with(
        batch_message_class(batch=[1, 2, 3, 4, 5], supports_batching=False)
    )


@pytest.mark.parametrize(
    "message_batcher_class,batch_message_class",
    [
        (
            batchers.AddSpanFeedbackScoresBatchMessageBatcher,
            messages.AddSpanFeedbackScoresBatchMessage,
        ),
        (
            batchers.AddTraceFeedbackScoresBatchMessageBatcher,
            messages.AddTraceFeedbackScoresBatchMessage,
        ),
        (
            batchers.AddThreadsFeedbackScoresBatchMessageBatcher,
            messages.AddThreadsFeedbackScoresBatchMessage,
        ),
    ],
)
def test_add_feedback_scores_batch_message_batcher__more_than_max_batch_size_items_added__one_batch_flushed__some_data_remains_in_batcher(
    message_batcher_class,
    batch_message_class,
):
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = message_batcher_class(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    add_feedback_score_batch_messages = [
        messages.AddSpanFeedbackScoresBatchMessage(batch=[1, 2]),
        messages.AddSpanFeedbackScoresBatchMessage(batch=[3, 4, 5, 6]),
        messages.AddSpanFeedbackScoresBatchMessage(batch=[7, 8]),
    ]  # batcher doesn't care about the content

    for feedback_scores_batch in add_feedback_score_batch_messages:
        batcher.add(feedback_scores_batch)

    assert not batcher.is_empty()
    flush_callback.assert_called_once_with(
        batch_message_class(batch=[1, 2, 3, 4, 5], supports_batching=False)
    )
    flush_callback.reset_mock()

    batcher.flush()
    flush_callback.assert_called_once_with(
        batch_message_class(batch=[6, 7, 8], supports_batching=False)
    )
    assert batcher.is_empty()


@pytest.mark.parametrize(
    "message_batcher_class",
    [
        batchers.AddSpanFeedbackScoresBatchMessageBatcher,
        batchers.AddTraceFeedbackScoresBatchMessageBatcher,
        batchers.AddThreadsFeedbackScoresBatchMessageBatcher,
    ],
)
def test_add_feedback_scores_batch_message_batcher__batcher_doesnt_have_items__flush_is_called__flush_callback_NOT_called(
    message_batcher_class,
):
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = message_batcher_class(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    batcher.flush()
    flush_callback.assert_not_called()


@pytest.mark.parametrize(
    "message_batcher_class",
    [
        batchers.AddSpanFeedbackScoresBatchMessageBatcher,
        batchers.AddTraceFeedbackScoresBatchMessageBatcher,
        batchers.AddThreadsFeedbackScoresBatchMessageBatcher,
    ],
)
def test_add_feedback_scores_batch_message_batcher__ready_to_flush_returns_True__is_flush_interval_passed(
    message_batcher_class,
):
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5
    FLUSH_INTERVAL = 0.1

    batcher = message_batcher_class(
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


@pytest.mark.parametrize(
    "message_batcher_class,score_message_class",
    [
        (
            batchers.AddSpanFeedbackScoresBatchMessageBatcher,
            messages.FeedbackScoreMessage,
        ),
        (
            batchers.AddTraceFeedbackScoresBatchMessageBatcher,
            messages.FeedbackScoreMessage,
        ),
        (
            batchers.AddThreadsFeedbackScoresBatchMessageBatcher,
            messages.ThreadsFeedbackScoreMessage,
        ),
    ],
)
def test_add_feedback_scores_batch_message_batcher__accumulated_scores_exceed_memory_limit__split_into_batches(
    message_batcher_class,
    score_message_class,
):
    collected_messages = []

    MEMORY_LIMIT_MB = 3

    batcher = message_batcher_class(
        max_batch_size=1000,
        flush_callback=collected_messages.append,
        flush_interval_seconds=NOT_USED,
        batch_memory_limit_mb=MEMORY_LIMIT_MB,
    )

    # Each producer splits its own call by MAX_BATCH_SIZE_MB, so a single score
    # under the limit arrives in a message of its own.
    for i in range(4):
        batcher.add(
            messages.AddSpanFeedbackScoresBatchMessage(
                batch=[
                    score_message_class(
                        id=f"id-{i}",
                        project_name="project",
                        name="relevance",
                        value=1.0,
                        source="sdk",
                        reason="r" * fake_message_factory.ONE_MEGABYTE,
                    )
                ]
            )
        )

    batcher.flush()

    assert len(collected_messages) == 2
    for message in collected_messages:
        assert sequence_splitter.get_payload_size_MB(message.batch) <= MEMORY_LIMIT_MB
