from __future__ import annotations

from typing import Any, Iterable, List, Optional


def supports_image_input(model_name: Optional[str]) -> bool:
    return bool(model_name)


def flatten_multimodal_content(value: Any) -> Any:
    """Recursively flatten multimodal payloads into markdown placeholders."""

    if isinstance(value, list) and _looks_like_openai_content_list(value):
        parts: List[str] = []
        for item in value:
            item_type = str(item.get("type", "")).lower()
            if item_type == "text":
                parts.append(str(item.get("text", "")))
            elif item_type == "image_url":
                url = (
                    item.get("image_url", {}).get("url")
                    if isinstance(item.get("image_url"), dict)
                    else None
                )
                if url:
                    parts.append(f"<<<image>>>{url}<<<\\/image>>>")
        return "\n\n".join(part for part in parts if part)

    if isinstance(value, dict):
        return {key: flatten_multimodal_content(val) for key, val in value.items()}

    if isinstance(value, list):
        return [flatten_multimodal_content(item) for item in value]

    return value


def _looks_like_openai_content_list(value: Iterable[Any]) -> bool:
    for item in value:
        if not isinstance(item, dict) or "type" not in item:
            return False
    return True
