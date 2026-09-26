from typing import Type, Dict

from . import base_batcher, batchers, batch_manager
from .. import messages, message_queue
from ... import config as opik_config

CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 2.0
CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000

CREATE_TRACES_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 2.0
CREATE_TRACES_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000

FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 1.0
FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000

GUARDRAIL_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 1.0
GUARDRAIL_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000

EXPERIMENT_ITEMS_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 3.0
EXPERIMENT_ITEMS_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000


def create_batch_manager(
    queue: message_queue.MessageQueue[messages.BaseMessage],
) -> batch_manager.BatchManager:
    create_span_message_batcher_ = batchers.CreateSpanMessageBatcher(
        flush_interval_seconds=CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=queue.put,
    )

    create_trace_message_batcher_ = batchers.CreateTraceMessageBatcher(
        flush_interval_seconds=CREATE_TRACES_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=CREATE_TRACES_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=queue.put,
    )

    # The producers of these messages (Opik.log_spans_feedback_scores,
    # Opik.log_traces_feedback_scores, ThreadsClient.log_threads_feedback_scores)
    # split their input at MAX_BATCH_SIZE_MB, so the batchers have to re-split to
    # the same budget rather than the larger batcher default. Read here rather
    # than at import so the two sides cannot drift.
    feedback_scores_memory_limit_mb = opik_config.MAX_BATCH_SIZE_MB

    add_span_feedback_scores_batch_message_batcher = batchers.AddSpanFeedbackScoresBatchMessageBatcher(
        flush_interval_seconds=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        batch_memory_limit_mb=feedback_scores_memory_limit_mb,
        flush_callback=queue.put,
    )

    add_trace_feedback_scores_batch_message_batcher = batchers.AddTraceFeedbackScoresBatchMessageBatcher(
        flush_interval_seconds=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        batch_memory_limit_mb=feedback_scores_memory_limit_mb,
        flush_callback=queue.put,
    )

    add_threads_feedback_scores_batch_message_batcher = batchers.AddThreadsFeedbackScoresBatchMessageBatcher(
        flush_interval_seconds=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=FEEDBACK_SCORES_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        batch_memory_limit_mb=feedback_scores_memory_limit_mb,
        flush_callback=queue.put,
    )

    guardrail_batch_message_batcher = batchers.GuardrailBatchMessageBatcher(
        flush_interval_seconds=GUARDRAIL_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=GUARDRAIL_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=queue.put,
    )

    experiment_items_batch_message_batcher = batchers.CreateExperimentItemsBatchMessageBatcher(
        flush_interval_seconds=EXPERIMENT_ITEMS_BATCH_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=EXPERIMENT_ITEMS_BATCH_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=queue.put,
    )

    message_to_batcher_mapping: Dict[
        Type[messages.BaseMessage], base_batcher.BaseBatcher
    ] = {
        messages.CreateSpanMessage: create_span_message_batcher_,
        messages.CreateTraceMessage: create_trace_message_batcher_,
        messages.AddSpanFeedbackScoresBatchMessage: add_span_feedback_scores_batch_message_batcher,
        messages.AddTraceFeedbackScoresBatchMessage: add_trace_feedback_scores_batch_message_batcher,
        messages.AddThreadsFeedbackScoresBatchMessage: add_threads_feedback_scores_batch_message_batcher,
        messages.GuardrailBatchMessage: guardrail_batch_message_batcher,
        messages.CreateExperimentItemsBatchMessage: experiment_items_batch_message_batcher,
    }

    batch_manager_ = batch_manager.BatchManager(
        message_to_batcher_mapping=message_to_batcher_mapping
    )

    return batch_manager_
