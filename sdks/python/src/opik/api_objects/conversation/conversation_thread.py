from typing import Any, Dict, List, Optional

import pydantic


class ConversationThreadItem(pydantic.BaseModel):
    """
    Represents a single message within a conversation thread.

    Each ConversationItem contains the role of the sender (e.g., 'user', 'assistant', 'system')
    and the content of the message. This structured format allows for consistent representation
    of messages across different conversation interfaces and evaluation systems.

    A message may also carry the `context` it was grounded on (e.g. the documents
    retrieved by a RAG pipeline for that turn). It is omitted from the serialized
    representation when not set.
    """

    role: str
    content: str
    context: Optional[List[str]] = None


class ConversationThread(pydantic.BaseModel):
    """
    Represents a conversation thread composed of multiple conversation items.

    This class is built using Pydantic's BaseModel to ensure type validation and data
    integrity. It maintains a list of conversation items, where each item is an
    instance of the `ConversationThreadItem` class. The conversation thread allows
    adding messages from various roles, such as assistant, user, and system, and
    provides the ability to export the conversation data as a JSON-serializable list.

    Attributes:
        discussion (List[ConversationThreadItem]): A list of conversation items
            representing the dialogue between the roles.
    """

    discussion: List[ConversationThreadItem] = pydantic.Field(default_factory=list)

    def add_item(self, item: ConversationThreadItem) -> None:
        self.discussion.append(item)

    def add_assistant_message(
        self, message: str, context: Optional[List[str]] = None
    ) -> None:
        self.add_item(
            ConversationThreadItem(role="assistant", content=message, context=context)
        )

    def add_user_message(self, message: str) -> None:
        self.add_item(ConversationThreadItem(role="user", content=message))

    def add_system_message(self, message: str) -> None:
        self.add_item(ConversationThreadItem(role="system", content=message))

    def as_json_list(self) -> List[Dict[str, Any]]:
        return [item.model_dump(exclude_none=True) for item in self.discussion]
