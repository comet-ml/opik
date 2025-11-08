"""
Reusable image utilities for multimodal datasets and optimizers.

Provides common functionality for:
- Encoding images to base64 data URIs
- Decoding base64 images
- Detecting image formats
- Converting between formats
- Validating image data
"""

import base64
import re
import warnings
from io import BytesIO
from typing import Any

try:  # Pillow is optional for text-only users
    from PIL import Image as _PillowImage
except ModuleNotFoundError as exc:  # pragma: no cover - optional dependency guard
    _PillowImage = None
    _PIL_IMPORT_ERROR = exc
else:  # pragma: no cover - import side-effects don't need coverage
    _PIL_IMPORT_ERROR = None

MIN_OPIK_PYTHON_VERSION = "1.9.4"


def _ensure_pillow() -> Any:
    if _PillowImage is None:
        raise ModuleNotFoundError(
            "Pillow is required for image helper utilities. Install it with "
            "`pip install Pillow` to use multimodal datasets."
        ) from _PIL_IMPORT_ERROR
    return _PillowImage


def warn_if_python_sdk_outdated(min_version: str = MIN_OPIK_PYTHON_VERSION) -> None:
    """
    Emit a warning when the installed Opik Python SDK is older than the required version.

    Args:
        min_version: Minimum supported SDK version string (semver).
    """
    try:
        import opik  # Local import to avoid optional dependency at module import time
    except Exception:
        warnings.warn(
            "Could not import the Opik Python SDK. Image datasets may not function correctly.",
            stacklevel=2,
        )
        return

    try:
        from packaging import version
    except Exception:
        warnings.warn(
            "Could not import 'packaging'. Please install it to enable Opik version checks.",
            stacklevel=2,
        )
        return

    installed_version = getattr(opik, "__version__", None)
    if not installed_version:
        warnings.warn(
            "Could not determine the Opik Python SDK version. "
            "Image datasets may require a newer release.",
            stacklevel=2,
        )
        return

    if version.parse(installed_version) < version.parse(min_version):
        warnings.warn(
            (
                "The Opik Python SDK version %s does not include full multimodal support. "
                "Please upgrade to %s or later to ensure image-based datasets work as expected."
            )
            % (installed_version, min_version),
            stacklevel=2,
        )


def encode_pil_to_base64_uri(image: Any, format: str = "PNG", quality: int = 85) -> str:
    """
    Encode a PIL Image to a base64 data URI.

    Args:
        image: PIL Image object
        format: Image format (PNG, JPEG, etc.)
        quality: JPEG quality (1-100), ignored for PNG

    Returns:
        Base64 data URI string (e.g., "data:image/png;base64,iVBORw...")

    Example:
        >>> from PIL import Image
        >>> img = Image.open("photo.jpg")
        >>> data_uri = encode_pil_to_base64_uri(img)
        >>> data_uri[:30]
        'data:image/png;base64,iVBORw0'
    """
    buffer = BytesIO()

    # Save with appropriate parameters
    save_kwargs = {"format": format}
    if format.upper() == "JPEG":
        save_kwargs["quality"] = quality
        save_kwargs["optimize"] = True

    image.save(buffer, **save_kwargs)

    # Encode to base64
    encoded = base64.b64encode(buffer.getvalue()).decode("utf-8")

    # Determine MIME type
    mime_type = f"image/{format.lower()}"
    if format.upper() == "JPEG":
        mime_type = "image/jpeg"

    return f"data:{mime_type};base64,{encoded}"


def encode_file_to_base64_uri(
    file_path: str, max_size: tuple[int, int] | None = None
) -> str:
    """
    Encode an image file to base64 data URI.

    Args:
        file_path: Path to image file
        max_size: Optional (width, height) to resize image

    Returns:
        Base64 data URI string

    Example:
        >>> uri = encode_file_to_base64_uri("dashcam.jpg", max_size=(800, 600))
    """
    PillowImage = _ensure_pillow()
    image = PillowImage.open(file_path)

    # Resize if needed
    if max_size:
        image.thumbnail(max_size, PillowImage.Resampling.LANCZOS)

    # Preserve original format or use PNG
    format = image.format or "PNG"

    return encode_pil_to_base64_uri(image, format=format)


def decode_base64_uri_to_pil(data_uri: str) -> Any:
    """
    Decode a base64 data URI to a PIL Image.

    Args:
        data_uri: Base64 data URI string

    Returns:
        PIL Image object

    Example:
        >>> uri = "data:image/png;base64,iVBORw0KGgo..."
        >>> img = decode_base64_uri_to_pil(uri)
        >>> img.size
        (800, 600)
    """
    # Extract base64 data
    if data_uri.startswith("data:"):
        # Format: data:image/png;base64,<data>
        base64_data = data_uri.split(",", 1)[1]
    else:
        # Already just base64
        base64_data = data_uri

    # Decode
    image_bytes = base64.b64decode(base64_data)
    buffer = BytesIO(image_bytes)

    PillowImage = _ensure_pillow()
    return PillowImage.open(buffer)


def is_base64_data_uri(value: str) -> bool:
    """
    Check if a string is a base64 data URI.

    Args:
        value: String to check

    Returns:
        True if value is a base64 data URI

    Example:
        >>> is_base64_data_uri("data:image/png;base64,iVBORw...")
        True
        >>> is_base64_data_uri("https://example.com/image.jpg")
        False
    """
    if not isinstance(value, str):
        return False

    # Check for data URI pattern
    pattern = r"^data:image/[a-z]+;base64,[A-Za-z0-9+/]+=*$"
    return bool(re.match(pattern, value, re.IGNORECASE))


def get_image_format_from_uri(data_uri: str) -> str | None:
    """
    Extract image format from a data URI.

    Args:
        data_uri: Base64 data URI

    Returns:
        Image format (e.g., "png", "jpeg") or None

    Example:
        >>> get_image_format_from_uri("data:image/jpeg;base64,...")
        'jpeg'
    """
    match = re.match(r"^data:image/([a-z]+);base64,", data_uri, re.IGNORECASE)
    if match:
        return match.group(1).lower()
    return None


def convert_to_structured_content(
    text: str, image_uri: str | None = None, image_detail: str = "auto"
) -> list[dict]:
    """
    Convert text and optional image to OpenAI structured content format.

    Args:
        text: Text content
        image_uri: Optional image data URI or URL
        image_detail: Image detail level ("auto", "low", "high")

    Returns:
        List of content parts in OpenAI format

    Example:
        >>> content = convert_to_structured_content(
        ...     "What's in this image?",
        ...     image_uri="data:image/png;base64,..."
        ... )
        >>> content
        [
            {"type": "text", "text": "What's in this image?"},
            {"type": "image_url", "image_url": {"url": "data:...", "detail": "auto"}}
        ]
    """
    parts = [{"type": "text", "text": text}]

    if image_uri:
        parts.append(
            {
                "type": "image_url",
                "image_url": {"url": image_uri, "detail": image_detail},
            }
        )

    return parts


def extract_images_from_structured_content(content: list[dict]) -> list[str]:
    """
    Extract all image URIs from structured content.

    Args:
        content: OpenAI-style structured content

    Returns:
        List of image URIs (data URIs or URLs)

    Example:
        >>> content = [
        ...     {"type": "text", "text": "Compare these:"},
        ...     {"type": "image_url", "image_url": {"url": "data:image/png;base64,..."}},
        ...     {"type": "image_url", "image_url": {"url": "https://example.com/img.jpg"}}
        ... ]
        >>> extract_images_from_structured_content(content)
        ['data:image/png;base64,...', 'https://example.com/img.jpg']
    """
    images = []

    for part in content:
        if part.get("type") == "image_url":
            url = part.get("image_url", {}).get("url")
            if url:
                images.append(url)

    return images


def validate_image_size(
    image: Any, max_width: int = 2048, max_height: int = 2048
) -> tuple[bool, str | None]:
    """
    Validate image dimensions.

    Args:
        image: PIL Image
        max_width: Maximum width
        max_height: Maximum height

    Returns:
        (is_valid, error_message)

    Example:
        >>> img = Image.new("RGB", (3000, 2000))
        >>> validate_image_size(img)
        (False, "Image width 3000 exceeds maximum 2048")
    """
    width, height = image.size

    if width > max_width:
        return False, f"Image width {width} exceeds maximum {max_width}"

    if height > max_height:
        return False, f"Image height {height} exceeds maximum {max_height}"

    return True, None


def estimate_image_tokens(image: Any, model: str = "gpt-4o-mini") -> int:
    """
    Estimate token count for an image based on model.

    Different vision models have different token costs per image.

    Args:
        image: PIL Image
        model: Vision model name

    Returns:
        Estimated token count

    Note:
        This is an approximation. Actual token usage may vary.

    Example:
        >>> img = Image.new("RGB", (512, 512))
        >>> estimate_image_tokens(img, "gpt-4o-mini")
        85
    """
    width, height = image.size

    # GPT-4o/4o-mini: 85 tokens per 512x512 tile (low detail)
    # High detail: scales with image size
    if "gpt-4o" in model.lower():
        # Simplified estimation
        if width <= 512 and height <= 512:
            return 85
        else:
            # High detail: ~170 tokens per 512x512 tile
            tiles_width = (width + 511) // 512
            tiles_height = (height + 511) // 512
            return 85 + (170 * tiles_width * tiles_height)

    # Claude: ~1600 tokens per image (approximation)
    elif "claude" in model.lower():
        return 1600

    # Gemini: Variable, ~258 tokens baseline
    elif "gemini" in model.lower():
        return 258

    # Default estimation
    return 500


__all__ = [
    "encode_pil_to_base64_uri",
    "encode_file_to_base64_uri",
    "decode_base64_uri_to_pil",
    "is_base64_data_uri",
    "get_image_format_from_uri",
    "convert_to_structured_content",
    "extract_images_from_structured_content",
    "validate_image_size",
    "estimate_image_tokens",
    "warn_if_python_sdk_outdated",
]
