from typing import TYPE_CHECKING, Any, Dict, Iterable, List, Mapping, Union, cast

if TYPE_CHECKING:
    import langchain_core.messages

ContentType = Union[str, List[Dict[str, Any]]]


_ROLE_TO_MESSAGE_CLASS: Mapping[str, str] = {
    "system": "SystemMessage",
    "user": "HumanMessage",
    "assistant": "AIMessage",
    "human": "HumanMessage",
    "ai": "AIMessage",
}


def convert_to_langchain_messages(
    messages: Iterable[Mapping[str, Any]],
) -> List["langchain_core.messages.BaseMessage"]:
    """Convert OpenAI-style chat messages to LangChain's primitives.

    Args:
        messages: Iterable of message dictionaries in the OpenAI schema. Each
            dictionary must include a ``role`` key and a ``content`` value that is
            either a string or a list of content blocks (``{"type": ..., ...}``).

    Returns:
        A list of LangChain ``BaseMessage`` instances preserving the original
        content structure.

    Raises:
        ValueError: If a message role is unsupported or required metadata is
            missing (for example ``tool_call_id`` on ``tool`` messages).
        TypeError: If a content payload is not a string or list.
    """

    import langchain_core.messages

    role_mapping = {
        role: getattr(langchain_core.messages, class_name)
        for role, class_name in _ROLE_TO_MESSAGE_CLASS.items()
    }

    langchain_messages: List["langchain_core.messages.BaseMessage"] = []
    for message in messages:
        payload: Mapping[str, Any] = message

        # messages_to_dict may wrap the payload under "data" for some message types
        if "content" not in payload and isinstance(message.get("data"), Mapping):
            payload = message["data"]  # type: ignore[index]

        role_value = (
            message.get("role")
            or message.get("type")
            or payload.get("role")
            or payload.get("type")
        )
        if role_value is None:
            raise ValueError("Message payload must include either 'role' or 'type'")

        role = str(role_value).lower()

        if "content" not in payload:
            raise ValueError("Message payload must include a 'content' field")

        content_raw = payload["content"]

        if not isinstance(content_raw, (str, list)):
            raise TypeError(
                f"Unsupported message content type {type(content_raw)!r} for role {role}"
            )

        content = cast(ContentType, content_raw)
        if role in role_mapping:
            message_cls = role_mapping[role]
            langchain_messages.append(message_cls(content=content))
            continue

        if role == "tool":
            tool_call_id = payload.get("tool_call_id") or message.get("tool_call_id")
            if not isinstance(tool_call_id, str):
                raise ValueError("Tool messages must include a 'tool_call_id' field")
            langchain_messages.append(
                langchain_core.messages.ToolMessage(
                    content=content,
                    tool_call_id=tool_call_id,
                )
            )
            continue

        if role == "function":
            name = payload.get("name") or message.get("name")
            if not isinstance(name, str):
                raise ValueError("Function messages must include a 'name' field")
            langchain_messages.append(
                langchain_core.messages.FunctionMessage(
                    content=content,
                    name=name,
                )
            )
            continue

        raise ValueError(f"Unsupported message role: {role}")

    return langchain_messages
