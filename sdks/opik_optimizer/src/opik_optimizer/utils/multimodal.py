"""
Reusable multimodal utilities for datasets and optimizers.

Currently focused on image helpers (encoding/decoding, validation) and generic
structured-content helpers that work for upcoming video/file attachment support.
"""

import base64
import copy
import re
import warnings
from io import BytesIO
from typing import Any, Iterable

_PillowImage: Any | None
_PIL_IMPORT_ERROR: ModuleNotFoundError | None

try:  # Pillow is optional for text-only users
    from PIL import Image as _ImportedPillowImage
except ModuleNotFoundError as exc:  # pragma: no cover - optional dependency guard
    _PillowImage = None
    _PIL_IMPORT_ERROR = exc
else:  # pragma: no cover - import side-effects don't need coverage
    _PillowImage = _ImportedPillowImage
    _PIL_IMPORT_ERROR = None

MIN_OPIK_PYTHON_VERSION = "1.9.4"

# ---------------------------------------------------------------------------
# Structured content metadata
# ---------------------------------------------------------------------------

MULTIMODAL_URL_FIELDS: dict[str, str] = {
    "image_url": "image_url",
    "video_url": "video_url",
    "file_url": "file_url",
}
SUPPORTED_MULTIMODAL_PART_TYPES: tuple[str, ...] = tuple(MULTIMODAL_URL_FIELDS.keys())
PLACEHOLDER_TOKENS: tuple[str, ...] = (
    "{image",
    "<<<image>>>",
    "{video",
    "<<<video>>>",
    "{file",
    "<<<file>>>",
)


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


def is_multimodal_part(part: Any) -> bool:
    """Return True if the structured content part is a supported multimodal attachment."""
    return (
        isinstance(part, dict)
        and isinstance(part.get("type"), str)
        and part["type"] in SUPPORTED_MULTIMODAL_PART_TYPES
    )


def contains_multimodal_placeholder(text: str) -> bool:
    """Return True if the string contains a placeholder token for multimodal content."""
    lowered = text.lower()
    return any(token in lowered for token in PLACEHOLDER_TOKENS)


def validate_structured_content_parts(content: list[dict[str, Any]]) -> None:
    """
    Validate OpenAI-style structured content parts.

    Raises:
        ValueError: if a part is malformed or uses an unsupported type.
    """
    for part in content:
        if not isinstance(part, dict):
            raise ValueError("Multimodal content parts must be dictionaries")

        part_type = part.get("type")
        if part_type == "text":
            if "text" not in part:
                raise ValueError("Text content part must include 'text' field")
            continue

        if part_type not in SUPPORTED_MULTIMODAL_PART_TYPES:
            allowed_types = "', '".join(("text", *SUPPORTED_MULTIMODAL_PART_TYPES))
            raise ValueError(
                f"Unknown content part type: {part_type}. Expected one of '{allowed_types}'"
            )

        payload_key = MULTIMODAL_URL_FIELDS[part_type]
        payload = part.get(payload_key)
        if not isinstance(payload, dict):
            raise ValueError(
                f"{part_type} content part must include '{payload_key}' dict payload"
            )

        media_url = payload.get("url")
        if not isinstance(media_url, str) or not media_url:
            raise ValueError(
                f"{part_type} payload must include non-empty string 'url' field"
            )


def replace_label_in_media_part(
    part: dict[str, Any], label: str, replacement: str
) -> dict[str, Any]:
    """
    Clone a media part and replace occurrences of a label in its URL.
    """
    if not is_multimodal_part(part):
        return copy.deepcopy(part)

    cloned = copy.deepcopy(part)
    payload_key = MULTIMODAL_URL_FIELDS[cloned["type"]]
    payload = cloned.get(payload_key, {})
    if isinstance(payload, dict) and isinstance(payload.get("url"), str):
        payload["url"] = payload["url"].replace(label, replacement)
    return cloned


def replace_label_in_multimodal_content(
    content: list[dict[str, Any]], label: str, replacement: str
) -> list[dict[str, Any]]:
    """
    Replace placeholders inside structured content (text + attachments).
    """
    new_parts: list[dict[str, Any]] = []
    for part in content:
        if not isinstance(part, dict):
            new_parts.append(copy.deepcopy(part))
            continue

        part_type = part.get("type")
        if part_type == "text":
            text_value = str(part.get("text", ""))
            new_parts.append({"type": "text", "text": text_value.replace(label, replacement)})
        elif part_type in SUPPORTED_MULTIMODAL_PART_TYPES:
            new_parts.append(replace_label_in_media_part(part, label, replacement))
        else:
            new_parts.append(copy.deepcopy(part))
    return new_parts


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
    save_kwargs: dict[str, Any] = {"format": format}
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
    text: str,
    image_uri: str | None = None,
    image_detail: str = "auto",
) -> list[dict[str, Any]]:
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
    parts: list[dict[str, Any]] = [{"type": "text", "text": text}]

    if image_uri:
        parts.append(
            {
                "type": "image_url",
                "image_url": {"url": image_uri, "detail": image_detail},
            }
        )

    return parts


def extract_images_from_structured_content(content: list[dict[str, Any]]) -> list[str]:
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


__all__ = [
    "encode_pil_to_base64_uri",
    "encode_file_to_base64_uri",
    "decode_base64_uri_to_pil",
    "is_base64_data_uri",
    "get_image_format_from_uri",
    "convert_to_structured_content",
    "extract_images_from_structured_content",
    "validate_image_size",
    "warn_if_python_sdk_outdated",
    "MULTIMODAL_URL_FIELDS",
    "SUPPORTED_MULTIMODAL_PART_TYPES",
    "PLACEHOLDER_TOKENS",
    "is_multimodal_part",
    "contains_multimodal_placeholder",
    "validate_structured_content_parts",
    "replace_label_in_media_part",
    "replace_label_in_multimodal_content",
]
