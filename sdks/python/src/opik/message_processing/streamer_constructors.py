import queue
from typing import Any, List

import httpx

from . import queue_consumer, message_processors, streamer
from ..file_upload import upload_manager, base_upload_manager
from ..rest_api import client as rest_api_client
from .batching import batch_manager_constuctors


def construct_online_streamer(
    rest_client: rest_api_client.OpikApi,
    httpx_client: httpx.Client,
    use_batching: bool,
    file_upload_worker_count: int,
    n_consumers: int = 1,
) -> streamer.Streamer:
    message_processor = message_processors.MessageSender(rest_client=rest_client)

    file_uploader = upload_manager.FileUploadManager(
        rest_client=rest_client,
        httpx_client=httpx_client,
        worker_count=file_upload_worker_count,
    )

    return construct_streamer(
        message_processor=message_processor,
        file_upload_manager=file_uploader,
        n_consumers=n_consumers,
        use_batching=use_batching,
    )


def construct_streamer(
    message_processor: message_processors.BaseMessageProcessor,
    file_upload_manager: base_upload_manager.BaseFileUploadManager,
    n_consumers: int,
    use_batching: bool,
) -> streamer.Streamer:
    message_queue: queue.Queue[Any] = queue.Queue()

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
        file_upload_manager=file_upload_manager,
    )

    return streamer_
