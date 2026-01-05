from typing import Optional, Union

from opik.message_processing import messages

from . import constants, preprocessor


class AttachmentsPreprocessor(preprocessor.MessagePreprocessor):
    def __init__(self, enabled: bool = True) -> None:
        self._enabled = enabled

    def preprocess(
        self, message: Optional[messages.BaseMessage]
    ) -> Optional[messages.BaseMessage]:
        """
        Processes a given message and ensures that it is converted into a specialized
        message type if applicable. If the message is already pre-processed, it
        returns the original message to avoid infinite recursion.

        Args:
            message: The message object to be processed.

        Returns:
            The processed message, either in its original form
            or converted into a message type supporting embedded attachments.
        """
        if not self._enabled:
            return message

        if message is None:
            # possibly already pre-processed by other preprocessors
            return None

        if hasattr(message, constants.MARKER_ATTRIBUTE_NAME):
            # already pre-processed - just return the original message to avoid infinite recursion
            return message

        if _has_potential_content_with_attachments(message):
            return messages.AttachmentSupportingMessage(message)
        else:
            return message


def _has_potential_content_with_attachments(message: messages.BaseMessage) -> bool:
    # Check if it's an Update message - always process these
    if isinstance(message, (messages.UpdateSpanMessage, messages.UpdateTraceMessage)):
        return _message_has_field_of_interest_set(message)

    # Check if it's a Create message with end_time set - only process these
    if isinstance(message, (messages.CreateSpanMessage, messages.CreateTraceMessage)):
        if message.end_time is not None:
            return _message_has_field_of_interest_set(message)

    # All other message types should not be wrapped
    return False


def _message_has_field_of_interest_set(
    message: Union[
        messages.UpdateSpanMessage,
        messages.UpdateTraceMessage,
        messages.CreateSpanMessage,
        messages.CreateTraceMessage,
    ],
) -> bool:
    return (
        message.input is not None
        or message.output is not None
        or message.metadata is not None
    )
