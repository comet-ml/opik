from typing import Any, Generator, List

from . import types
from .conversation_turns_factory import build_conversation_turns


def get_turns_in_sliding_window(
    turns: List[types.ConversationTurn], window_size: int
) -> Generator[List[types.ConversationTurn], Any, None]:
    """
    Generate sliding windows of conversation turns.
    """

    for index in range(len(turns)):
        yield turns[max(0, index - window_size + 1) : index + 1]


def merge_turns(turns: List[types.ConversationTurn]) -> types.Conversation:
    """
    Flatten a list of :class:`ConversationTurn` objects into message dictionaries.
    """

    output: types.Conversation = []
    for turn in turns:
        output.extend(turn.as_list())
    return output


def extract_turns_windows_from_conversation(
    conversation: types.Conversation, window_size: int
) -> List[types.Conversation]:
    """
    Break the conversation into overlapping windows of turns.
    """

    if len(conversation) == 0:
        raise ValueError("Conversation is empty")

    turns = build_conversation_turns(conversation=conversation)
    if len(turns) == 0:
        raise ValueError("Conversation has no turns")

    return [
        merge_turns(turn_window)
        for turn_window in get_turns_in_sliding_window(turns, window_size)
    ]


__all__ = [
    "get_turns_in_sliding_window",
    "merge_turns",
    "extract_turns_windows_from_conversation",
]
