from typing import Dict, List, Literal, Optional

import pydantic

ConversationDict = Dict[Literal["role", "content"], str]
Conversation = List[ConversationDict]


class ConversationTurn(pydantic.BaseModel):
    """Representation of a user/assistant exchange."""

    input: ConversationDict
    output: Optional[ConversationDict]

    def as_list(self) -> List[ConversationDict]:
        if self.output is None:
            return [self.input]
        return [self.input, self.output]


__all__ = ["ConversationDict", "Conversation", "ConversationTurn"]
