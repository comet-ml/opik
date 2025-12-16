import mimetypes
import random
import time
from typing import Optional


# The attachment file name regex
ATTACHMENT_FILE_NAME_REGEX = r"(?:input|output|metadata)-attachment-\d+-\d+-sdk\.\w+"
ATTACHMENT_FILE_NAME_PLACEHOLDER_REGEX = (
    r"\[((?:input|output|metadata)-attachment-\d+-\d+-sdk\.\w+)\]"
)


def get_file_extension(mime_type: str) -> str:
    """Convert MIME type to file extension.

    Mirrors the Java getFileExtension() method in AttachmentStripperService.

    Args:
        mime_type: The MIME type (e.g., "image/png", "application/pdf")

    Returns:
        File extension without a leading dot (e.g., "png", "pdf")
    """
    if not mime_type:
        return "bin"

    # Try to get extension from mimetypes module
    extension = mimetypes.guess_extension(mime_type, strict=False)

    if extension:
        # Remove the leading dot
        extension = extension.lstrip(".")
        # Handle special cases where mimetypes returns less common extensions
        if mime_type == "image/jpeg" and extension == "jpe":
            return "jpg"
        return extension

    # Fallback: extract from the MIME type (e.g., "image/png" -> "png")
    if "/" in mime_type:
        subtype = mime_type.split("/")[1]
        # Handle special cases like "svg+xml" -> "svg"
        if "+" in subtype:
            subtype = subtype.split("+")[0]
        # Remove any parameters (e.g., "jpeg; charset=utf-8" -> "jpeg")
        subtype = subtype.split(";")[0].strip()
        return subtype

    return "bin"


def detect_mime_type(data: bytes) -> Optional[str]:
    """Detect MIME type from byte content using magic bytes.

    This provides basic MIME type detection similar to Apache Tika in the Java implementation.
    It checks common file format magic bytes.

    Args:
        data: The byte data to analyze

    Returns:
        Detected MIME type string, or "application/octet-stream" if unknown
    """
    if len(data) < 4:
        return "application/octet-stream"

    # Check common file format magic bytes
    # PNG
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"

    # JPEG
    if data[:2] == b"\xff\xd8" and data[-2:] == b"\xff\xd9":
        return "image/jpeg"

    # GIF
    if data[:6] in (b"GIF87a", b"GIF89a"):
        return "image/gif"

    # PDF
    if data[:4] == b"%PDF":
        return "application/pdf"

    # WebP
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"

    # SVG (XML-based, check for SVG tag)
    try:
        text = data[:1024].decode("utf-8", errors="ignore")
        if "<svg" in text.lower():
            return "image/svg+xml"
    except Exception:
        pass

    # MP4
    if len(data) >= 12 and data[4:8] == b"ftyp":
        return "video/mp4"

    # JSON
    try:
        text = data[:100].decode("utf-8", errors="strict").strip()
        if text.startswith("{") or text.startswith("["):
            return "application/json"
    except Exception:
        pass

    # Default to octet-stream for unknown types
    return "application/octet-stream"


def create_attachment_filename(context: str, extension: str) -> str:
    """
    Generates a unique attachment filename based on the provided context and file extension.

    This function creates a filename by combining the given context, a randomly generated
    prefix to ensure uniqueness, the current timestamp in milliseconds, and the provided
    file extension. The generated filename aligns with the backend convention for naming
    attachments, which includes specific formatting and structure.

    Args:
        context: The context to use as the base for the filename (e.g., "input",
            "output", or "metadata").
        extension: The file extension to use for the filename (e.g., "png",
            "jpg", "txt").

    Returns:
        A generated filename string in the format
            "{context}-attachment-{random_prefix}-{timestamp}.{extension}".
    """
    # The backend has the following naming convention: r"\\[((?:input|output|metadata)-attachment-\\d+-\\d+\\.\\w+)\\]"
    # Example: [input-attachment-1-1704067200000.png]

    timestamp = int(round(time.time() * 1000))
    # we need to generate a large enough random prefix to avoid collisions
    random_prefix = random.randint(1, 99999999)
    return f"{context}-attachment-{random_prefix}-{timestamp}-sdk.{extension}"
