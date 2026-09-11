"""Normalize URL-safe base64 binary blobs to the standard base64 alphabet.

Some upstream SDKs (notably google.genai, which sets pydantic's
``ser_json_bytes='base64'`` on its BaseModel) emit URL-safe base64, with '-'
and '_' in place of '+' and '/'. Opik's downstream consumers — the SDK
attachments extractor in this package, and the frontend's inline image
rendering — only match the standard alphabet, so an unnormalized URL-safe blob
is silently truncated at the first '-' or '_' (see OPIK-6387).

Detection is content-based: we decode the first few bytes and require an image
file signature before rewriting, so that unrelated values which happen to
share the URL-safe alphabet (e.g. UUIDs) are left untouched.
"""

import base64
import binascii
import re
from typing import Any

from . import decoder_helpers

_URLSAFE_BASE64_RE = re.compile(r"[A-Za-z0-9_-]+={0,2}")
_MIN_BASE64_IMAGE_LENGTH = 24


def normalize_urlsafe_base64_images_in_place(node: Any) -> None:
    """Walk a nested dict/list and rewrite any URL-safe-base64-encoded image
    string leaves to standard base64. Non-string leaves are ignored.
    """
    if isinstance(node, dict):
        for key, child in node.items():
            if isinstance(child, str):
                if is_urlsafe_base64_image(child):
                    node[key] = urlsafe_to_standard_base64(child)
            else:
                normalize_urlsafe_base64_images_in_place(child)
    elif isinstance(node, list):
        for index, item in enumerate(node):
            if isinstance(item, str):
                if is_urlsafe_base64_image(item):
                    node[index] = urlsafe_to_standard_base64(item)
            else:
                normalize_urlsafe_base64_images_in_place(item)


def is_urlsafe_base64_image(value: str) -> bool:
    """True if ``value`` is a URL-safe base64 encoding of an image whose
    header bytes match a known signature (PNG, JPEG, GIF, WebP/RIFF, TIFF).

    Returns False for strings already in the standard alphabet (no '-' or
    '_'), so callers can use this as a cheap "needs rewriting?" check.
    """
    if len(value) < _MIN_BASE64_IMAGE_LENGTH:
        return False
    if "-" not in value and "_" not in value:
        return False
    if not _URLSAFE_BASE64_RE.fullmatch(value):
        return False
    head = value[:16]
    head += "=" * (-len(head) % 4)
    try:
        decoded = base64.urlsafe_b64decode(head)
    except (binascii.Error, ValueError):
        return False
    return decoder_helpers.detect_image_mime_type_from_header(decoded) is not None


def urlsafe_to_standard_base64(value: str) -> str:
    return value.replace("-", "+").replace("_", "/")
