from typing import Any, Generator, List, Mapping, Sequence

import opik.exceptions as exceptions

from . import types
from .conversation_turns_factory import build_conversation_turns


def get_turns_in_sliding_window(
    turns: List[types.ConversationTurn], window_size: int
) -> Generator[List[types.ConversationTurn], Any, None]:
    """
    Generates windows of conversation turns of a fixed size from a list of turns.
    This function creates a sliding window over the list of conversation turns.
    Each window includes the current turn and up to `window_size - 1` previous
    conversation turns. If there are fewer turns available than the `window_size`,
    the window will consist of all available turns up to the current turn.
    Args:
        turns: List of conversation turn objects representing the interactions
            in a conversation.
        window_size: Integer specifying the maximum number of turns to include
            in each window.
    Yields:
        A generator that produces lists of conversation turns, where each list
        represents a sliding window of turns.
    """

    for i in range(len(turns)):
        yield turns[max(0, i - window_size + 1) : i + 1]


def merge_turns(turns: List[types.ConversationTurn]) -> types.Conversation:
    """
    Merges a list of conversation turns into a single conversation.
    This function takes a list of conversation turns and combines them
    into a single conversation by extending the output list with the data
    from each turn.
    Args:
        turns: A list of conversation turn  objects to be combined.
    Returns:
        types.Conversation: A combined conversation object containing all
            the turns from the input list.
    """

    output: types.Conversation = []
    for turn in turns:
        output.extend(turn.as_list())
    return output


def extract_turns_windows_from_conversation(
    conversation: types.Conversation, window_size: int
) -> List[types.Conversation]:
    """
    Extracts a list of conversation windows based on turns using a sliding window
    approach. This function divides a conversation into consecutive overlapping
    windows, where each window contains a specified number of turns.
    Args:
        conversation: The input conversation from which turns will be processed.
        window_size: The number of turns to include in each sliding window.
    Returns:
        A list of conversations, each representing a window of turns specified
        by the given window size.
    Raises:
        ValueError: If the conversation is empty or if it has no turns.
    """

    if len(conversation) == 0:
        raise ValueError("Conversation is empty")

    turns = build_conversation_turns(conversation=conversation)
    if len(turns) == 0:
        raise ValueError("Conversation has no turns")

    turns_windows: List[types.Conversation] = [
        merge_turns(turns_window)
        for turns_window in get_turns_in_sliding_window(turns, window_size)
    ]
    return turns_windows


def render_turns_for_prompt(
    conversation: types.Conversation, include_context: bool = False
) -> str:
    """Renders a conversation for interpolation into a judge prompt.

    ``role`` and ``content`` are always emitted. Messages may carry extra keys -
    today ``context``, the documents an answer was generated from - and prompts must
    opt into those explicitly rather than inherit them by stringifying the message
    dicts, which would silently change what the judge sees whenever a key is added.

    With ``include_context``, a message's documents are rendered next to the message
    they belong to, so the judge sees each answer alongside what it was based on.
    """
    rendered = []
    for message in conversation:
        turn = {"role": message["role"], "content": message["content"]}
        if include_context and message.get("context"):
            turn["context"] = message["context"]  # type: ignore[assignment]
        rendered.append(turn)
    return str(rendered)


def message_has_text(message: Mapping[str, Any]) -> bool:
    """Whether a conversation message carries text to read.

    An assistant message can reach the conversation-level metrics without any text:
    the output transform that builds a conversation returns the trace's output, and
    ``create_conversation_from_traces`` skips a message only when that is ``None``,
    so a turn whose text is empty -- an agent call that only issued tool calls, or
    an empty completion -- arrives as ``""``. ``None`` is the one non-text value read
    as "no text", because an output transform returns it for a turn it has nothing to
    say for.

    The content shapes are read the way the GEval conversation adapters read them
    (``llm_judges.g_eval_wrappers._text_from_parts``), so both families agree on
    which turns carry text. A shape this cannot read is reported by raising rather
    than read as empty: reading it as empty would silently drop a message that does
    have an answer.
    """
    content = message.get("content")
    if content is None:
        return False
    if isinstance(content, str):
        return bool(content.strip())
    if isinstance(content, (list, tuple)):
        return any(text.strip() for text in _text_parts(content))
    raise _bad_content_error(content)


def _text_parts(parts: Sequence[Any]) -> List[str]:
    """The text carried by the text parts of a content list.

    Mirrors the GEval conversation adapters' reading of the same shape: a string and
    a mapping with string ``text`` carry text, a mapping without one carries none
    (an image part, say), and any other shape is one neither can read.
    """
    texts: List[str] = []
    for part in parts:
        if isinstance(part, str):
            texts.append(part)
        elif isinstance(part, dict):
            if "text" in part:
                text = part["text"]
                if not isinstance(text, str):
                    raise _bad_content_error(part)
                texts.append(text)
            elif part.get("type") == "text":
                raise _bad_content_error(part)
        else:
            raise _bad_content_error(part)
    return texts


def _bad_content_error(content: Any) -> exceptions.MetricComputationError:
    return exceptions.MetricComputationError(
        "Assistant turn content must be text or a list of content parts, "
        f"got {type(content).__name__}."
    )


__all__ = [
    "get_turns_in_sliding_window",
    "merge_turns",
    "extract_turns_windows_from_conversation",
    "message_has_text",
    "render_turns_for_prompt",
]
