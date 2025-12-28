from __future__ import annotations

import base64
import os
from io import BytesIO
from typing import Any


def encode_image_to_base64_uri(
    image: Any,
    *,
    image_format: str,
    quality: int | None = None,
) -> str | None:
    if image is None:
        return None
    if isinstance(image, str) and image.startswith("data:image/"):
        return image

    payload: bytes | None = None
    format_hint = image_format
    if isinstance(image, bytes):
        payload = image
    elif isinstance(image, dict):
        raw = image.get("bytes")
        if isinstance(raw, bytes):
            payload = raw
        else:
            path = image.get("path")
            if path and os.path.exists(path):
                with open(path, "rb") as handle:
                    payload = handle.read()
    elif hasattr(image, "save"):
        buffer = BytesIO()
        fmt = getattr(image, "format", None)
        format_hint = fmt or image_format
        save_kwargs: dict[str, Any] = {"format": format_hint}
        if format_hint.upper() in {"JPG", "JPEG"} and quality is not None:
            save_kwargs["quality"] = quality
            save_kwargs["optimize"] = True
        image.save(buffer, **save_kwargs)
        payload = buffer.getvalue()

    if payload is None:
        return None

    mime = "image/png"
    if format_hint.upper() in {"JPG", "JPEG"}:
        mime = "image/jpeg"
    encoded = base64.b64encode(payload).decode("utf-8")
    return f"data:{mime};base64,{encoded}"
