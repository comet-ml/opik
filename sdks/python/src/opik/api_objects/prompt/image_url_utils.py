"""Utilities for handling image URLs in chat prompts.

This module provides functions to convert image URLs to base64 data URLs
to ensure reliable access when sending to LLM APIs that may have network
restrictions or cannot access certain external URLs.
"""

import base64
import logging
from typing import Optional
import httpx

LOGGER = logging.getLogger(__name__)


def is_data_url(url: str) -> bool:
    """Check if a URL is already a base64 data URL.

    Args:
        url: The URL to check.

    Returns:
        True if the URL is a data URL (starts with "data:"), False otherwise.
    """
    return url.startswith("data:")


def convert_image_url_to_data_url(url: str, timeout: float = 10.0) -> Optional[str]:
    """Convert an image URL to a base64-encoded data URL.

    This function downloads the image from the URL and converts it to a
    base64 data URL. This ensures the image can be accessed by LLM APIs
    that may have network restrictions or cannot access certain external URLs.

    Args:
        url: The image URL to convert.
        timeout: Request timeout in seconds. Defaults to 10.0.

    Returns:
        A base64-encoded data URL (e.g., "data:image/jpeg;base64,..."),
        or None if the conversion failed.

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
        headers = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"}

        with httpx.Client(timeout=timeout) as client:
            response = client.get(url, headers=headers, follow_redirects=True)
            response.raise_for_status()

            # Determine MIME type from Content-Type header or URL extension
            content_type = response.headers.get("Content-Type", "")
            if not content_type or not content_type.startswith("image/"):
                # Try to infer from URL
                url_lower = url.lower()
                if url_lower.endswith(".jpg") or url_lower.endswith(".jpeg"):
                    content_type = "image/jpeg"
                elif url_lower.endswith(".png"):
                    content_type = "image/png"
                elif url_lower.endswith(".gif"):
                    content_type = "image/gif"
                elif url_lower.endswith(".webp"):
                    content_type = "image/webp"
                else:
                    # Default to JPEG if we can't determine
                    content_type = "image/jpeg"

            # Convert to base64
            encoded = base64.b64encode(response.content).decode("utf-8")
            data_url = f"data:{content_type};base64,{encoded}"

            return data_url

    except Exception as e:
        LOGGER.warning(
            f"Failed to convert image URL to data URL: {url}. Error: {e}. "
            "The original URL will be used, but it may fail if the LLM API "
            "cannot access it."
        )
        # Return None to indicate failure - caller can decide what to do
        return None
