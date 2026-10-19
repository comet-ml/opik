"""Reading the text a conversation message carries.

Conversation-level metrics need to agree on which messages carry text: an assistant
message can reach them without any (``create_conversation_from_traces`` skips one
only when the output transform returns ``None``, so a turn whose text is empty --
an agent call that only issued tool calls, or an empty completion -- arrives as
``""``). This module is the one place that reads the content shapes, so the
sliding-window judges and the GEval conversation adapters cannot drift apart.
"""

from typing import Any, List

import opik.exceptions as exceptions

from . import types

# The ``type`` values of the non-text multimodal parts this module recognizes. A
# mapping part with one of these carries no text but is a known shape, so it is
# skipped; any other mapping is a shape no adapter can read, so it is reported.
_NON_TEXT_PART_TYPES = frozenset(
    {
        "image",
        "image_url",
        "input_audio",
        "audio",
        "audio_url",
        "video",
        "video_url",
        "file",
        "refusal",
    }
)


def message_has_text(message: types.ConversationDict) -> bool:
    """Whether a conversation message carries text to read.

    ``None`` is the one non-text value read as "no text", because an output
    transform returns it for a turn it has nothing to say for. A shape this cannot
    read is reported by raising rather than read as empty: reading it as empty
    would silently drop a message that does have an answer.
    """
    content = message.get("content")
    if content is None:
        return False
    if isinstance(content, str):
        return bool(content.strip())
    if isinstance(content, (list, tuple)):
        return bool(text_from_parts(content).strip())
    raise bad_content_error(content)


def text_from_parts(parts: Any) -> str:
    """The text of a multimodal content list, or ``""`` when it carries none.

    A string part and a mapping with string ``text`` carry text. A mapping with a
    known non-text ``type`` (an image or audio part, say) carries none and is
    skipped. Any other shape -- a mapping with no ``text`` and no known ``type``, a
    mapping whose ``text`` is not a string, or a part that is neither a string nor
    a mapping -- is one no adapter can read, so it is reported by raising rather
    than counted as an empty turn: counting it as empty would grade an older turn
    and report success. The public conversation type still declares string content;
    list/tuple handling is defensive support for direct ``score()`` calls, while
    ``evaluate_threads`` continues to normalize its output to strings.
    """
    texts: List[str] = []
    for part in parts:
        if isinstance(part, str):
            texts.append(part)
        elif isinstance(part, dict):
            if "text" in part:
                text = part["text"]
                if not isinstance(text, str):
                    raise bad_content_error(part)
                texts.append(text)
            else:
                part_type = part.get("type")
                if isinstance(part_type, str) and part_type in _NON_TEXT_PART_TYPES:
                    continue
                raise bad_content_error(part)
        else:
            raise bad_content_error(part)

    return "\n".join(text for text in texts if text.strip())


def bad_content_error(value: Any) -> exceptions.MetricComputationError:
    """The error for a content shape no conversation adapter can read."""
    return exceptions.MetricComputationError(
        "Assistant turn content must be text or a list of content parts, "
        f"got {type(value).__name__}."
    )
