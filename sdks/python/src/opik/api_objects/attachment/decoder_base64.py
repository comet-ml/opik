import base64
import binascii
import logging
import tempfile
from typing import Any, Optional, Literal

from . import attachment, decoder, decoder_helpers

LOGGER = logging.getLogger(__name__)


class Base64AttachmentDecoder(decoder.AttachmentDecoder):
    """Decodes base64 encoded attachment data.

    This decoder decodes base64 strings, detects MIME types from content, and creates Attachment objects.
    """

    def decode(
        self,
        raw_data: str,
        context: Literal["input", "output", "metadata"] = "input",
        **kwargs: Any,
    ) -> Optional[attachment.Attachment]:
        """Decode base64 encoded data into an Attachment object.

        Args:
            raw_data: Base64 encoded string data
            context: Context string for filename generation.

        Returns:
            Attachment object with decoded data, or None if decoding fails or type is not recognizable
        """
        if not isinstance(raw_data, str):
            LOGGER.warning("Attachment data is not a string, skipping.")
            return None

        try:
            # Decode base64 string to bytes
            decoded_bytes = base64.b64decode(raw_data, validate=True)

            # Detect MIME type from content
            mime_type = decoder_helpers.detect_mime_type(decoded_bytes)

            # Skip if not a recognizable file type
            if not mime_type or mime_type in ("application/octet-stream", "text/plain"):
                LOGGER.debug("Attachment type is not recognized, skipping.")
                return None

            # Get file extension from the MIME type
            extension = decoder_helpers.get_file_extension(mime_type)

            # Generate filename
            file_name = decoder_helpers.create_attachment_filename(
                context, extension=extension
            )

            # Save decoded bytes to a temporary file
            temp_file = tempfile.NamedTemporaryFile(
                mode="wb", delete=False, suffix=extension
            )
            temp_file.write(decoded_bytes)
            temp_file.flush()
            temp_file.close()

            # Return Attachment object with a file path
            return attachment.Attachment(
                data=temp_file.name, file_name=file_name, content_type=mime_type
            )

        except (ValueError, binascii.Error) as e:
            LOGGER.debug(
                "Failed to decode attachment data, reason: invalid base64. Reason: %s",
                e,
                exc_info=True,
            )
            # Not valid base64, return None
            return None
        except Exception as ex:
            LOGGER.warning(
                "Failed to decode attachment data, reason: %s", ex, exc_info=True
            )
            # Unexpected error, return None to avoid crashing the pipeline
            return None
