import sys
from typing import List, Optional

import pydantic
from typing_extensions import TypedDict

if sys.version_info < (3, 11):
    from typing_extensions import Required
else:
    from typing import Required


class ConversationDict(TypedDict, total=False):
    """A single message of a conversation thread.

    ``role`` and ``content`` are always set. ``context`` - the documents the answer
    was grounded on - is present only on agent messages of conversations built with a
    ``trace_context_transform`` (see ``opik.evaluation.evaluate_threads``).
    """

    role: Required[str]
    content: Required[str]
    context: List[str]


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


__all__ = ["ConversationDict", "Conversation", "ConversationTurn"]
