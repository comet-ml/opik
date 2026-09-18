from typing import List

from . import types


def build_conversation_turns(
    conversation: types.Conversation,
) -> List[types.ConversationTurn]:
    """
    Builds a list of conversation turns from a given conversation object by grouping
    user and assistant message pairs together. Each turn is represented by a
    `ConversationTurn` object containing a single user's input and the assistant's
    corresponding output.

    Args:
        conversation (types.Conversation): A conversation object containing a list
            of dictionaries. Each dictionary represents a message in the conversation
            with a "role" key indicating the sender ("user" or "assistant") and
            message content.

    Returns:
        List[types.ConversationTurn]: A list of `ConversationTurn` objects, where
            each object represents a pair of user input and assistant output
            messages. A user message that no assistant message answers is
            returned as a turn with `output=None`, so it stays in the
            conversation instead of being replaced by the next user message.
    """
    turns = []
    user_input = None
    for message_dict in conversation:
        if message_dict["role"] == "user":
            # A user message that went unanswered is still a turn - the tail branch
            # below relies on that. It has to stay true mid-conversation too, otherwise
            # the next user message overwrites the pending one and that message never
            # reaches the judges, which score the windows built from these turns.
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
