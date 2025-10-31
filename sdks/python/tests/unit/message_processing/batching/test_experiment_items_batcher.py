from unittest import mock
import time


from opik.message_processing.batching import batchers
from opik.message_processing import messages

NOT_USED = None


def test_create_experiment_items_batch_message_batcher__exactly_max_batch_size_reached__batch_is_flushed():
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 5

    batcher = batchers.CreateExperimentItemsBatchMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    assert batcher.is_empty()
    experiment_item_batch_messages = [
        messages.CreateExperimentItemsBatchMessage(
            batch=[
                messages.ExperimentItemMessage(
                    id="exp_item_1",
                    experiment_id="exp1",
                    trace_id="trace1",
                    dataset_item_id="item1",
                ),
                messages.ExperimentItemMessage(
                    id="exp_item_2",
                    experiment_id="exp1",
                    trace_id="trace2",
                    dataset_item_id="item2",
                ),
            ]
        ),
        messages.CreateExperimentItemsBatchMessage(
            batch=[
                messages.ExperimentItemMessage(
                    id="exp_item_3",
                    experiment_id="exp1",
                    trace_id="trace3",
                    dataset_item_id="item3",
                ),
                messages.ExperimentItemMessage(
                    id="exp_item_4",
                    experiment_id="exp1",
                    trace_id="trace4",
                    dataset_item_id="item4",
                ),
                messages.ExperimentItemMessage(
                    id="exp_item_5",
                    experiment_id="exp1",
                    trace_id="trace5",
                    dataset_item_id="item5",
                ),
            ]
        ),
    ]

    for experiment_items_batch in experiment_item_batch_messages:
        batcher.add(experiment_items_batch)
    assert batcher.is_empty()

    # Verify that flush callback was called with all items combined
    flush_callback.assert_called_once()
    call_args = flush_callback.call_args[0][0]
    assert isinstance(call_args, messages.CreateExperimentItemsBatchMessage)
    assert len(call_args.batch) == 5
    assert call_args.supports_batching is False


def test_create_experiment_items_batch_message_batcher__more_than_max_batch_size__multiple_flushes():
    flush_callback = mock.Mock()

    MAX_BATCH_SIZE = 3

    batcher = batchers.CreateExperimentItemsBatchMessageBatcher(
        max_batch_size=MAX_BATCH_SIZE,
        flush_callback=flush_callback,
        flush_interval_seconds=NOT_USED,
    )

    # Add 2 messages
    batcher.add(
        messages.CreateExperimentItemsBatchMessage(
            batch=[
                messages.ExperimentItemMessage(
                    id="exp_item_1",
                    experiment_id="exp1",
                    trace_id="trace1",
                    dataset_item_id="item1",
                ),
                messages.ExperimentItemMessage(
                    id="exp_item_2",
                    experiment_id="exp1",
                    trace_id="trace2",
                    dataset_item_id="item2",
                ),
            ]
        )
    )
    assert flush_callback.call_count == 0

    # Add 2 more messages (total 4, exceeds max of 3)
    batcher.add(
        messages.CreateExperimentItemsBatchMessage(
            batch=[
                messages.ExperimentItemMessage(
                    id="exp_item_3",
                    experiment_id="exp1",
                    trace_id="trace3",
                    dataset_item_id="item3",
                ),
                messages.ExperimentItemMessage(
                    id="exp_item_4",
                    experiment_id="exp1",
                    trace_id="trace4",
                    dataset_item_id="item4",
                ),
            ]
        )
    )

    # Should have flushed the first batch of 3
    assert flush_callback.call_count == 1
    first_call_args = flush_callback.call_args_list[0][0][0]
    assert len(first_call_args.batch) == 3

    # The batcher should have 1 item left
    assert not batcher.is_empty()
    assert batcher.size() == 1


def test_create_experiment_items_batch_message_batcher__flush_interval_reached__batch_is_flushed():
    flush_callback = mock.Mock()

    FLUSH_INTERVAL = 0.1  # 100ms

    batcher = batchers.CreateExperimentItemsBatchMessageBatcher(
        max_batch_size=1000,  # High enough to not trigger size-based flush
        flush_callback=flush_callback,
        flush_interval_seconds=FLUSH_INTERVAL,
    )

    # Add a message
    batcher.add(
        messages.CreateExperimentItemsBatchMessage(
            batch=[
                messages.ExperimentItemMessage(
                    id="exp_item_1",
                    experiment_id="exp1",
                    trace_id="trace1",
                    dataset_item_id="item1",
                ),
            ]
        )
    )

    # Batcher should not be empty yet
    assert not batcher.is_empty()
    assert not batcher.is_ready_to_flush()

    # Wait for flush interval
    time.sleep(FLUSH_INTERVAL + 0.05)

    # Now it should be ready to flush
    assert batcher.is_ready_to_flush()

    # Manual flush
    batcher.flush()

    # Should have flushed
    assert flush_callback.call_count == 1
    assert batcher.is_empty()


def test_create_experiment_items_batch_message_batcher__empty_batch__nothing_flushed():
    flush_callback = mock.Mock()

    batcher = batchers.CreateExperimentItemsBatchMessageBatcher(
        max_batch_size=5,
        flush_callback=flush_callback,
        flush_interval_seconds=1.0,
    )

    # Flush empty batcher
    batcher.flush()

    # Should not have called the callback
    flush_callback.assert_not_called()
