"""Apply addressed text edits without asking a model to regenerate message structure."""

from __future__ import annotations

import copy
import re
from typing import Any

from ..api_objects.evidence import TextEdit

from ..api_objects.chat_prompt import ChatPrompt

_PLACEHOLDER = re.compile(r"\{([A-Za-z_][\w.\-]*)\}")


def describe_text_segments(
    prompt: ChatPrompt,
    allowed_roles: set[str] | None = None,
) -> list[dict[str, str]]:
    segments = []
    for index, message in enumerate(prompt.get_messages()):
        role = message["role"]
        if allowed_roles is not None and role not in allowed_roles:
            continue
        content = message.get("content")
        prefix = f"message:{index}"
        if isinstance(content, str):
            segments.append({"segment_id": prefix, "role": role, "text": content})
        elif isinstance(content, list):
            for part_index, part in enumerate(content):
                if isinstance(part, dict) and part.get("type") == "text":
                    segments.append(
                        {
                            "segment_id": f"{prefix}:part:{part_index}",
                            "role": role,
                            "text": part["text"],
                        }
                    )
    return segments


def apply_text_edits(
    prompt: ChatPrompt,
    edits: list[TextEdit] | list[dict[str, Any]],
    allowed_roles: set[str] | None = None,
    *,
    preserve_placeholders: bool = True,
) -> ChatPrompt:
    """Validate the entire edit set, then change only addressed text on a copy."""
    available = {
        segment["segment_id"]: segment
        for segment in describe_text_segments(prompt, allowed_roles)
    }
    validated = [
        edit if isinstance(edit, TextEdit) else TextEdit.model_validate(edit)
        for edit in edits
    ]
    seen = set()
    for edit in validated:
        if edit.segment_id not in available:
            raise ValueError(
                f"Text edit targets an unknown or non-editable segment: {edit.segment_id}"
            )
        if edit.segment_id in seen:
            raise ValueError(f"Duplicate text edit: {edit.segment_id}")
        seen.add(edit.segment_id)
        original = available[edit.segment_id]["text"]
        missing = set(_PLACEHOLDER.findall(original)) - set(
            _PLACEHOLDER.findall(edit.text)
        )
        if preserve_placeholders and missing:
            raise ValueError(
                f"Text edit {edit.segment_id} drops dataset placeholders: {sorted(missing)}"
            )
    messages = copy.deepcopy(prompt.get_messages())
    for edit in validated:
        parts = edit.segment_id.split(":")
        message = messages[int(parts[1])]
        if len(parts) == 2:
            message["content"] = edit.text
        else:
            message["content"][int(parts[3])]["text"] = edit.text
    result = prompt.copy()
    result.set_messages(messages)
    return result
