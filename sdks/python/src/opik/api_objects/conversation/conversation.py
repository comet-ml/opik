from typing import Dict, List

import pydantic


class ConversationItem(pydantic.BaseModel):
    """
    Represents a single message within a conversation thread.

    Each ConversationItem contains the role of the sender (e.g., 'user', 'assistant', 'system')
    and the content of the message. This structured format allows for consistent representation
    of messages across different conversation interfaces and evaluation systems.
    """

    role: str
    content: str


class Conversation(pydantic.BaseModel):
    """
    Represents a conversation thread consisting of multiple messages exchanged between participants.

    This class models a structured conversation as a list of message dictionaries, where each
    dictionary contains information about the message such as the role of the sender (e.g., 'user',
    'assistant') and the content of the message. Used for evaluation and analysis of conversation
    threads in the OPIK system.
    """

    discussion: List[ConversationItem] = pydantic.Field(default_factory=list)

    def add_item(self, item: ConversationItem) -> None:
        self.discussion.append(item)

    def add_assistant_message(self, message: str) -> None:
        self.add_item(ConversationItem(role="assistant", content=message))

    def add_user_message(self, message: str) -> None:
        self.add_item(ConversationItem(role="user", content=message))

    def add_system_message(self, message: str) -> None:
        self.add_item(ConversationItem(role="system", content=message))

    def as_json_list(self) -> List[Dict[str, str]]:
        return [item.model_dump() for item in self.discussion]
