import queue
from typing import Type, Dict

from .. import messages

from . import base_batcher
from . import batchers
from . import batch_manager

CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 1.0
CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000

FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 1.0
FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000


def create_batch_manager(message_queue: queue.Queue) -> batch_manager.BatchManager:
    create_span_message_batcher_ = batchers.CreateSpanMessageBatcher(
        flush_interval_seconds=CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=message_queue.put,
    )

    create_trace_message_batcher_ = batchers.CreateTraceMessageBatcher(
        flush_interval_seconds=CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=message_queue.put,
    )

    add_span_feedback_scores_batch_message_batcher = batchers.AddSpanFeedbackScoresBatchMessageBatcher(
        flush_interval_seconds=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=message_queue.put,
    )

    add_trace_feedback_scores_batch_message_batcher = batchers.AddTraceFeedbackScoresBatchMessageBatcher(
        flush_interval_seconds=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=message_queue.put,
    )

    message_to_batcher_mapping: Dict[
        Type[messages.BaseMessage], base_batcher.BaseBatcher
    ] = {
        messages.CreateSpanMessage: create_span_message_batcher_,
        messages.CreateTraceMessage: create_trace_message_batcher_,
        messages.AddSpanFeedbackScoresBatchMessage: add_span_feedback_scores_batch_message_batcher,
        messages.AddTraceFeedbackScoresBatchMessage: add_trace_feedback_scores_batch_message_batcher,
    }

    batch_manager_ = batch_manager.BatchManager(
        message_to_batcher_mapping=message_to_batcher_mapping
    )

    return batch_manager_
