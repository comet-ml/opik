from typing import List

from . import types


def build_conversation_turns(
    conversation: types.Conversation,
) -> List[types.ConversationTurn]:
    """
    Builds a list of conversation turns from a given conversation object by pairing each
    user message with the assistant message that answers it. A turn holds the user's input
    and, when an assistant message follows it, that message as the output.

    Args:
        conversation (types.Conversation): A conversation object containing a list
            of dictionaries. Each dictionary represents a message in the conversation
            with a "role" key indicating the sender ("user" or "assistant") and
            message content.

    Returns:
        List[types.ConversationTurn]: A list of `ConversationTurn` objects, one per
            user message. A user message that no assistant message answers is
            returned as a turn with `output=None`, so it stays in the
            conversation instead of being replaced by the next user message.
    """
    turns = []
    user_input = None
    for message_dict in conversation:
        if message_dict["role"] == "user":
            # An unanswered user message stays a turn of its own, as it already is
            # in the tail branch below; overwriting it here would drop a message.
            if user_input is not None:
                turns.append(types.ConversationTurn(input=user_input, output=None))
            user_input = message_dict
        elif message_dict["role"] == "assistant" and user_input is not None:
            current_turn = types.ConversationTurn(input=user_input, output=message_dict)
            turns.append(current_turn)
            user_input = None

    # append the last user input if it exists
    if user_input is not None:
        turns.append(types.ConversationTurn(input=user_input, output=None))

    return turns
