import queue

from .. import messages

from . import create_span_message_batcher
from . import batch_manager


def create_batch_manager(message_queue: queue.Queue) -> batch_manager.BatchManager:
    create_span_message_batcher_ = create_span_message_batcher.CreateSpanMessageBatcher(
        flush_interval=1, max_batch_size=1000, flush_callback=message_queue.put
    )

    MESSAGE_TO_BATCHER_MAPPING = {
        messages.CreateSpanMessage: create_span_message_batcher_
    }

    batch_manager_ = batch_manager.BatchManager(
        message_to_batcher_mapping=MESSAGE_TO_BATCHER_MAPPING
    )

    return batch_manager_
