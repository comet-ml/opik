import queue
from typing import Any, List

from . import queue_consumer, message_processors, streamer
from ..rest_api import client as rest_api_client


def construct_online_streamer(
    rest_client: rest_api_client.OpikApi, n_consumers: int = 1
) -> streamer.Streamer:
    message_processor = message_processors.MessageSender(rest_client=rest_client)

    return construct_streamer(message_processor, n_consumers)


def construct_streamer(
    message_processor: message_processors.BaseMessageProcessor,
    n_consumers: int,
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

    streamer_ = streamer.Streamer(
        message_queue=message_queue, queue_consumers=queue_consumers
    )

    return streamer_
