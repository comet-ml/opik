import logging
from typing import Optional, NamedTuple, List, Literal, cast

from opik.api_objects.attachment import (
    attachments_extractor,
    attachment_context,
    converters,
)

from . import message_processors
from ..preprocessing import constants
from .. import messages, streamer


LOGGER = logging.getLogger(__name__)


class EntityDetails(NamedTuple):
    entity_type: Literal["span", "trace"]
    entity_id: str
    project_name: str


class AttachmentsExtractionProcessor(message_processors.BaseMessageProcessor):
    """
    Class for processing message attachments through extraction and further handling.

    The AttachmentsExtractionProcessor class is designed to handle attachments from incoming
    messages. It checks the type of messages and processes them if they support
    attachments. This includes extracting attachment data, replacing them with references,
    and streaming processed or original messages through a pipeline. The class provides a
    mechanism to toggle processing activity and ensures proper handling of messages with
    embedded attachment information.
    """

    def __init__(
        self,
        min_attachment_size: int,
        messages_streamer: streamer.Streamer,
        url_override: str,
        is_active: bool = True,
    ):
        """
        Initializes an object with essential components for managing message streaming
        and attachment extraction.

        Args:
            min_attachment_size: Minimum size for an attachment to be extracted.
            messages_streamer: The streamer that is responsible for managing
                messages broadcasts.
            url_override: A custom URL to override default configurations if set.
            is_active: Indicator of whether this instance is active. Default is True.
        """
        self._is_active = is_active
        self.extractor = attachments_extractor.AttachmentsExtractor(min_attachment_size)
        self.messages_streamer = messages_streamer
        self._url_override = url_override

        self.attachment_attributes = ["input", "output", "metadata"]

    def is_active(self) -> bool:
        return self._is_active

    def process(self, message: messages.BaseMessage) -> None:
        if not isinstance(message, messages.AttachmentSupportingMessage):
            return

        if self._is_active:
            # do attachment processing only if the processor is active
            try:
                self._process_attachments_in_message(message.original_message)
            except Exception as ex:
                LOGGER.error(
                    "Failed to process attachment support message: %s", ex, exc_info=ex
                )

        # put the original message into the streamer for further processing
        original_message = message.original_message
        setattr(original_message, constants.MARKER_ATTRIBUTE_NAME, True)
        self.messages_streamer.put(original_message)

    def _process_attachments_in_message(self, original: messages.BaseMessage) -> None:
        entity_details = entity_type_from_attachment_message(original)
        if entity_details is None:
            LOGGER.error(
                "Failed to extract entity details from message - %s. Skipping embedded attachments processing.",
                original.__class__.__name__,
            )
            return

        attachments = []

        for attribute in self.attachment_attributes:
            if getattr(original, attribute, None):
                results = self.extractor.extract_and_replace(
                    data=getattr(original, attribute),
                    entity_type=entity_details.entity_type,
                    entity_id=entity_details.entity_id,
                    project_name=entity_details.project_name,
                    context=cast(Literal["input", "output", "metadata"], attribute),
                )
                attachments.extend(results)

        if len(attachments) > 0:
            LOGGER.debug(
                "Extracted %d attachments from %s (entity: %s/%s)",
                len(attachments),
                original.__class__.__name__,
                entity_details.entity_type,
                entity_details.entity_id,
            )

            self._process_attachments(attachments)
        else:
            LOGGER.debug(
                "No attachments found in the message - %s.", original.__class__.__name__
            )

    def _process_attachments(
        self, attachments: List[attachment_context.AttachmentWithContext]
    ) -> None:
        for attachment in attachments:
            create_attachment_message = converters.attachment_to_message(
                attachment_data=attachment.attachment_data,
                entity_type=attachment.entity_type,
                entity_id=attachment.entity_id,
                project_name=attachment.project_name,
                url_override=self._url_override,
                delete_after_upload=True,  # make sure to delete attachments after upload to avoid leaking space and data
            )
            self.messages_streamer.put(create_attachment_message)


def entity_type_from_attachment_message(
    message: messages.BaseMessage,
) -> Optional[EntityDetails]:
    if isinstance(message, (messages.CreateSpanMessage, messages.UpdateSpanMessage)):
        return EntityDetails("span", message.span_id, project_name=message.project_name)
    elif isinstance(
        message, (messages.CreateTraceMessage, messages.UpdateTraceMessage)
    ):
        return EntityDetails(
            "trace", message.trace_id, project_name=message.project_name
        )
    else:
        return None
