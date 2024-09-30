import queue
from typing import Any, List

from . import queue_consumer, message_processors, streamer
from ..rest_api import client as rest_api_client
from .batching import batch_manager_constuctors


def construct_online_streamer(
    rest_client: rest_api_client.OpikApi,
    use_batching: bool,
    n_consumers: int = 1,
) -> streamer.Streamer:
    message_processor = message_processors.MessageSender(rest_client=rest_client)

    return construct_streamer(message_processor, n_consumers, use_batching)


def construct_streamer(
    message_processor: message_processors.BaseMessageProcessor,
    n_consumers: int,
    use_batching: bool,
) -> streamer.Streamer:
    message_queue: "queue.Queue[Any]" = queue.Queue()

    queue_consumers: List[queue_consumer.QueueConsumer] = [
        queue_consumer.QueueConsumer(
            message_queue=message_queue,
            message_processor=message_processor,
            name=f"QueueConsumerThread_{i}",
        )
        for i in range(n_consumers)
    ]

    batch_manager = (
        batch_manager_constuctors.create_batch_manager(message_queue)
        if use_batching
        else None
    )

    streamer_ = streamer.Streamer(
        message_queue=message_queue,
        queue_consumers=queue_consumers,
        batch_manager=batch_manager,
    )

    return streamer_
