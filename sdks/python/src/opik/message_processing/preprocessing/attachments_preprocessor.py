from typing import Optional

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

        if isinstance(
            message,
            (
                messages.CreateSpanMessage,
                messages.UpdateSpanMessage,
                messages.CreateTraceMessage,
                messages.UpdateTraceMessage,
            ),
        ):
            return messages.AttachmentSupportingMessage(message)
        else:
            return message
