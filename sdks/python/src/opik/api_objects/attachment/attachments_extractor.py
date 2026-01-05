import re
from typing import Dict, Any, Literal, List, NamedTuple

from . import attachment, attachment_context, decoder_base64


class ExtractionResult(NamedTuple):
    attachments: List[attachment.Attachment]
    sanitized_data: Any


class AttachmentsExtractor:
    """
    Extracts and processes attachments embedded as Base64 strings within data structures.

    This class is designed to identify and decode Base64-encoded attachments located
    within the provided data. It uses a regular expression pattern to search for
    Base64 strings that meet a specified minimum length. Extracted attachments are
    decoded and replaced with sanitized placeholders in the original data.
    """

    def __init__(self, min_attachment_size: int):
        """
        Initializes the class with a minimum attachment size and configures the base64
        pattern for decoding attachments based on its length.

        Args:
            min_attachment_size: The minimum size of the attachment in characters
                for it to be considered valid. This ensures that only large enough
                base64 strings are matched to minimize false positives.
        """
        self._min_attachment_size = min_attachment_size
        self.decoder = decoder_base64.Base64AttachmentDecoder()

        # Pattern to match base64 strings (can be embedded in text)
        # Requires at least min_attachment_size characters to reduce false positives
        min_base64_groups = int(min_attachment_size / 4)
        BASE64_PATTERN = (
            r"(?:[A-Za-z0-9+/]{4}){"
            + str(min_base64_groups)
            + ",}(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?"
        )
        self.pattern = re.compile(BASE64_PATTERN)

    def extract_and_replace(
        self,
        data: Dict[str, Any],
        entity_type: Literal["span", "trace"],
        entity_id: str,
        project_name: str,
        context: Literal["input", "output", "metadata"],
    ) -> List[attachment_context.AttachmentWithContext]:
        # iterate over all items and extract attachments
        attachments: List[attachment_context.AttachmentWithContext] = []
        for key, value in data.items():
            extraction_result = self._try_extract_attachments(value, context)
            if extraction_result.attachments:
                # replace the original value with the sanitized one and collect attachments
                data[key] = extraction_result.sanitized_data
                for extracted_attachment in extraction_result.attachments:
                    attachments.append(
                        attachment_context.AttachmentWithContext(
                            attachment_data=extracted_attachment,
                            entity_type=entity_type,
                            entity_id=entity_id,
                            project_name=project_name,
                            context=context,
                        )
                    )

        return attachments

    def _try_extract_attachments(
        self, data: Any, context: Literal["input", "output", "metadata"]
    ) -> ExtractionResult:
        """
        Recursively extract attachments from data that can be a string, dict, list, or other type.

        Args:
            data: The data to process (can be str, dict, list, or other types)
            context: The context where the data is located (input, output, or metadata)

        Returns:
            ExtractionResult with extracted attachments and sanitized data
        """
        # Handle string data - check for base64 attachments
        if isinstance(data, str):
            return self._extract_from_string(data, context)

        # Handle dictionary data - recursively process each value
        elif isinstance(data, dict):
            return self._extract_from_dict(data, context)

        # Handle list data - recursively process each element
        elif isinstance(data, list):
            return self._extract_from_list(data, context)

        # For other types (int, bool, None, etc.), return as-is
        else:
            return ExtractionResult(attachments=[], sanitized_data=data)

    def _extract_from_string(
        self, data: str, context: Literal["input", "output", "metadata"]
    ) -> ExtractionResult:
        """Extract attachments from a string value."""
        if len(data) < self._min_attachment_size:
            # skip short strings
            return ExtractionResult(attachments=[], sanitized_data=data)

        attachments: List[attachment.Attachment] = []
        sanitized_data = data
        for match in self.pattern.finditer(data):
            to_decode = match.group()
            decoded_attachment = self.decoder.decode(to_decode, context)
            if decoded_attachment is not None:
                attachments.append(decoded_attachment)
                sanitized_data = sanitized_data.replace(
                    to_decode, f"[{decoded_attachment.file_name}]"
                )

        return ExtractionResult(attachments=attachments, sanitized_data=sanitized_data)

    def _extract_from_dict(
        self, data: Dict[str, Any], context: Literal["input", "output", "metadata"]
    ) -> ExtractionResult:
        """Recursively extract attachments from a dictionary."""
        all_attachments: List[attachment.Attachment] = []
        sanitized_dict = {}

        for key, value in data.items():
            result = self._try_extract_attachments(value, context)
            sanitized_dict[key] = result.sanitized_data
            all_attachments.extend(result.attachments)

        return ExtractionResult(
            attachments=all_attachments, sanitized_data=sanitized_dict
        )

    def _extract_from_list(
        self, data: List[Any], context: Literal["input", "output", "metadata"]
    ) -> ExtractionResult:
        """Recursively extract attachments from a list."""
        all_attachments: List[attachment.Attachment] = []
        sanitized_list = []

        for item in data:
            result = self._try_extract_attachments(item, context)
            sanitized_list.append(result.sanitized_data)
            all_attachments.extend(result.attachments)

        return ExtractionResult(
            attachments=all_attachments, sanitized_data=sanitized_list
        )
