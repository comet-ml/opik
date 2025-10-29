from typing import TYPE_CHECKING, Any, Dict, List, Mapping, TypedDict, Union

if TYPE_CHECKING:
    import langchain_core.messages

ContentType = Union[str, List[Dict[str, Any]]]


class ChatMessageDict(TypedDict):
    role: str
    content: ContentType

_ROLE_TO_MESSAGE_CLASS: Mapping[str, str] = {
    "system": "SystemMessage",
    "user": "HumanMessage",
    "assistant": "AIMessage",
}


def convert_to_langchain_messages(
    messages: List[ChatMessageDict],
) -> List["langchain_core.messages.BaseMessage"]:
    """
    Convert OpenAI-style chat messages to LangChain's message objects.

    LangChain accepts either plain strings or lists of ``{"type": ..., ...}``
    dictionaries (see ``HumanMessage.__init__`` in ``langchain-core``), so the
    structured multimodal payloads can be forwarded without modification.
    """

    import langchain_core.messages

    role_mapping = {
        role: getattr(langchain_core.messages, class_name)
        for role, class_name in _ROLE_TO_MESSAGE_CLASS.items()
    }

    langchain_messages: List["langchain_core.messages.BaseMessage"] = []
    for message in messages:
        role = message["role"]
        content = message["content"]

        if role not in role_mapping:
            raise ValueError(f"Unsupported message role: {role}")
        message_cls = role_mapping[role]

        if not isinstance(content, (str, list)):
            raise TypeError(
                f"Unsupported message content type {type(content)!r} for role {role}"
            )

        langchain_messages.append(message_cls(content=content))

    return langchain_messages
