import queue
from typing import Type, Dict

from .. import messages

from . import base_batcher
from . import create_span_message_batcher
from . import batch_manager

CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS = 1.0
CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE = 1000


def create_batch_manager(message_queue: queue.Queue) -> batch_manager.BatchManager:
    create_span_message_batcher_ = create_span_message_batcher.CreateSpanMessageBatcher(
        flush_interval_seconds=CREATE_SPANS_MESSAGE_BATCHER_FLUSH_INTERVAL_SECONDS,
        max_batch_size=CREATE_SPANS_MESSAGE_BATCHER_MAX_BATCH_SIZE,
        flush_callback=message_queue.put,
    )

    MESSAGE_TO_BATCHER_MAPPING: Dict[
        Type[messages.BaseMessage], base_batcher.BaseBatcher
    ] = {messages.CreateSpanMessage: create_span_message_batcher_}

    batch_manager_ = batch_manager.BatchManager(
        message_to_batcher_mapping=MESSAGE_TO_BATCHER_MAPPING
    )

    return batch_manager_
