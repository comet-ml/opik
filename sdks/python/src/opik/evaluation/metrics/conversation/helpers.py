from typing import Any, Generator, List

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


__all__ = [
    "get_turns_in_sliding_window",
    "merge_turns",
    "extract_turns_windows_from_conversation",
    "render_turns_for_prompt",
]
