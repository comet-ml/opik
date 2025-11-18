"""Utilities for handling image URLs in chat prompts.

This module provides functions to convert image URLs to base64 data URLs
to ensure reliable access when sending to LLM APIs that may have network
restrictions or cannot access certain external URLs.
"""

import base64
import logging
import httpx

from ...exceptions import ImageConversionError

LOGGER = logging.getLogger(__name__)

# Standard User-Agent string for HTTP requests
USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"


def _infer_mime_type_from_url(url: str) -> str:
    """Infer MIME type from URL file extension.

    Args:
        url: The image URL to analyze.

    Returns:
        A MIME type string (e.g., "image/jpeg", "image/png").
        Defaults to "image/jpeg" if the type cannot be determined.
    """
    url_lower = url.lower()
    if url_lower.endswith((".jpg", ".jpeg")):
        return "image/jpeg"
    elif url_lower.endswith(".png"):
        return "image/png"
    elif url_lower.endswith(".gif"):
        return "image/gif"
    elif url_lower.endswith(".webp"):
        return "image/webp"
    else:
        # Default to JPEG if we can't determine
        return "image/jpeg"


def is_data_url(url: str) -> bool:
    """Check if a URL is already a base64 data URL.

    Args:
        url: The URL to check.

    Returns:
        True if the URL is a data URL (starts with "data:"), False otherwise.
    """
    return url.startswith("data:")


def convert_image_url_to_data_url(url: str, timeout: float = 10.0) -> str:
    """Convert an image URL to a base64-encoded data URL.

    This function downloads the image from the URL and converts it to a
    base64 data URL. This ensures the image can be accessed by LLM APIs
    that may have network restrictions or cannot access certain external URLs.

    Args:
        url: The image URL to convert.
        timeout: Request timeout in seconds. Defaults to 10.0.

    Returns:
        A base64-encoded data URL (e.g., "data:image/jpeg;base64,...").

    Raises:
        ImageConversionError: If the conversion fails due to network errors,
            invalid responses, or encoding issues.

    Example:
        >>> url = "https://example.com/image.jpg"
        >>> data_url = convert_image_url_to_data_url(url)
        >>> # Returns: "data:image/jpeg;base64,/9j/4AAQSkZJRg..."
    """
    if is_data_url(url):
        # Already a data URL, return as-is
        return url

    try:
        # Download the image with proper headers
        headers = {"User-Agent": USER_AGENT}

        with httpx.Client(timeout=timeout) as client:
            response = client.get(url, headers=headers, follow_redirects=True)
            response.raise_for_status()

            # Determine MIME type from Content-Type header or URL extension
            content_type = response.headers.get("Content-Type", "")
            if not content_type or not content_type.startswith("image/"):
                # Try to infer from URL
                content_type = _infer_mime_type_from_url(url)

            # Convert to base64
            try:
                encoded = base64.b64encode(response.content).decode("utf-8")
            except UnicodeDecodeError as e:
                raise ImageConversionError(
                    f"Failed to decode image content as UTF-8 for URL: {url}"
                ) from e

            data_url = f"data:{content_type};base64,{encoded}"
            return data_url

    except httpx.HTTPError as e:
        raise ImageConversionError(
            f"HTTP error while downloading image from {url}: {e}"
        ) from e
    except httpx.TimeoutException as e:
        raise ImageConversionError(
            f"Timeout while downloading image from {url} (timeout: {timeout}s)"
        ) from e
