import copy
from typing import TypeVar

from . import anonymizer
from ..message_processing import messages

MessageType = TypeVar("MessageType", bound=messages.BaseMessage)


def anonymize_message(
    message: MessageType, message_anonymizer: anonymizer.Anonymizer
) -> MessageType:
    """Anonymize sensitive data in Opik messages.

    This function specifically targets CreateSpanMessage and CreateTraceMessage,
    anonymizing their input, output, and metadata fields. All other message
    types are returned unchanged.

    Args:
        message: The message to anonymize.
        message_anonymizer: The anonymizer instance to use for anonymization.

    Returns:
        A copy of the message with anonymized fields, or the original message
        if it's not a supported type for anonymization.
    """
    # Only anonymize CreateSpanMessage and CreateTraceMessage
    if not isinstance(
        message, (messages.CreateSpanMessage, messages.CreateTraceMessage)
    ):
        return message

    # Create a copy to avoid modifying the original message
    anonymized_message = copy.copy(message)

    # Anonymize the input field if present
    if anonymized_message.input is not None:
        anonymized_message.input = message_anonymizer.anonymize(
            anonymized_message.input
        )

    # Anonymize the output field if present
    if anonymized_message.output is not None:
        anonymized_message.output = message_anonymizer.anonymize(
            anonymized_message.output
        )

    # Anonymize metadata field if present
    if anonymized_message.metadata is not None:
        anonymized_message.metadata = message_anonymizer.anonymize(
            anonymized_message.metadata
        )

    return anonymized_message
