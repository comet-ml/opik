from typing import Any, Generator

from . import types


def get_turns_in_sliding_window(
    conversation: types.Conversation, window_size: int
) -> Generator[types.Conversation, Any, None]:
    for i in range(len(conversation)):
        yield conversation[max(0, i - window_size + 1) : i + 1]
