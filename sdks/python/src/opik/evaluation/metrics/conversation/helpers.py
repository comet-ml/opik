from typing import Any, Generator, List

from . import types


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
    output = []
    for turn in turns:
        output.extend(turn.as_list())
    return output
