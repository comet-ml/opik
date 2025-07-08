from typing import Dict, Literal, List, Optional

import pydantic

ConversationDict = Dict[Literal["role", "content"], str]
Conversation = List[ConversationDict]


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
    output: Optional[ConversationDict]

    def as_list(self) -> List[ConversationDict]:
        if self.output is None:
            return [self.input]

        return [self.input, self.output]
