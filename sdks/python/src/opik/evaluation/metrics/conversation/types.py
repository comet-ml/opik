from typing import Literal

import pydantic

ConversationDict = dict[Literal["role", "content"], str]
Conversation = list[ConversationDict]


class ConversationTurn(pydantic.BaseModel):
    """
    Representation of a single turn in a conversation.
    This class defines a model for encapsulating a single conversational
    turn consisting of an input user's message and an output LLM message. It is
    designed to handle the exchange of messages in a structured format.
    Args:
        input: The input message of the conversation turn.
        output: The output message of the conversation turn.
    Example:
        >>> conversation_turn = ConversationTurn(
        >>>     input={"role": "user", "content": "Hello!"},
        >>>     output={"role": "assistant", "content": "Hi there! How can I help you today?"}
        >>> )
    """

    input: ConversationDict
    output: ConversationDict | None

    def as_list(self) -> list[ConversationDict]:
        if self.output is None:
            return [self.input]
        return [self.input, self.output]


__all__ = ["ConversationDict", "Conversation", "ConversationTurn"]
