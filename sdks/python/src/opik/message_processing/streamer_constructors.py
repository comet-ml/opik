from typing import List, Optional

import httpx

from . import (
    queue_consumer,
    messages,
    message_queue,
    streamer,
)
from .batching import batch_manager_constuctors
from .preprocessing import (
    attachments_preprocessor,
    batching_preprocessor,
    file_upload_preprocessor,
)
from .processors import attachments_extraction_processor, message_processors
from ..file_upload import upload_manager
from ..rest_api import client as rest_api_client


def construct_online_streamer(
    rest_client: rest_api_client.OpikApi,
    httpx_client: httpx.Client,
    use_batching: bool,
    use_attachment_extraction: bool,
    min_base64_embedded_attachment_size: int,
    file_upload_worker_count: int,
    n_consumers: int,
    max_queue_size: int,
    message_processor: message_processors.ChainedMessageProcessor,
    url_override: str,
) -> streamer.Streamer:
    file_uploader = upload_manager.FileUploadManager(
        rest_client=rest_client,
        httpx_client=httpx_client,
        worker_count=file_upload_worker_count,
    )

    streamer = construct_streamer(
        message_processor=message_processor,
        upload_preprocessor=file_upload_preprocessor.FileUploadPreprocessor(
            file_uploader
        ),
        n_consumers=n_consumers,
        use_batching=use_batching,
        use_attachment_extraction=use_attachment_extraction,
        max_queue_size=max_queue_size,
    )

    # add attachment extraction processor to the beginning of the processing chain
    attachment_extraction = (
        attachments_extraction_processor.AttachmentsExtractionProcessor(
            messages_streamer=streamer,
            min_attachment_size=min_base64_embedded_attachment_size,
            url_override=url_override,
            is_active=use_attachment_extraction,
        )
    )
    message_processor.add_first(attachment_extraction)

    return streamer


def construct_streamer(
    message_processor: message_processors.BaseMessageProcessor,
    upload_preprocessor: file_upload_preprocessor.FileUploadPreprocessor,
    n_consumers: int,
    use_batching: bool,
    use_attachment_extraction: bool,
    max_queue_size: Optional[int],
) -> streamer.Streamer:
    message_queue_: message_queue.MessageQueue[messages.BaseMessage] = (
        message_queue.MessageQueue(max_length=max_queue_size)
    )

    queue_consumers: List[queue_consumer.QueueConsumer] = [
        queue_consumer.QueueConsumer(
            queue=message_queue_,
            message_processor=message_processor,
            name=f"QueueConsumerThread_{i}",
        )
        for i in range(n_consumers)
    ]

    batch_manager = (
        batch_manager_constuctors.create_batch_manager(message_queue_)
        if use_batching
        else None
    )

    streamer_ = streamer.Streamer(
        queue=message_queue_,
        queue_consumers=queue_consumers,
        batch_preprocessor=batching_preprocessor.BatchingPreprocessor(batch_manager),
        upload_preprocessor=upload_preprocessor,
        attachments_preprocessor=attachments_preprocessor.AttachmentsPreprocessor(
            use_attachment_extraction
        ),
    )

    return streamer_
