from typing import List

from . import types


def build_conversation_turns(
    conversation: types.Conversation,
) -> List[types.ConversationTurn]:
    """Group user/assistant messages into structured conversation turns."""

    turns: List[types.ConversationTurn] = []
    user_input: types.ConversationDict | None = None

    for message_dict in conversation:
        role = message_dict.get("role")
        if role == "user":
            user_input = message_dict
        elif role == "assistant" and user_input is not None:
            turns.append(
                types.ConversationTurn(input=user_input, output=message_dict)
            )
            user_input = None

    if user_input is not None:
        turns.append(types.ConversationTurn(input=user_input, output=None))

    return turns


__all__ = ["build_conversation_turns"]

