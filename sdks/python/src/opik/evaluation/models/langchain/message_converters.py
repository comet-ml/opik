from typing import Any, Dict, List, TYPE_CHECKING

if TYPE_CHECKING:
    from langchain import schema


def convert_to_langchain_messages(
    messages: List[Dict[str, Any]],
) -> List["schema.BaseMessage"]:
    langchain_messages = []
    for message in messages:
        role = message["role"]
        content = message["content"]

        if role == "system":
            langchain_messages.append(schema.SystemMessage(content=content))
        elif role == "user":
            langchain_messages.append(schema.HumanMessage(content=content))
        elif role == "assistant":
            langchain_messages.append(schema.AIMessage(content=content))
        # Could handle other message types if needed

    return langchain_messages


def convert_from_langchain_message(message: "schema.BaseMessage") -> Dict[str, str]:
    """
    Convert a Langchain message to standard format.

    Args:
        message: A Langchain message object

        Returns:
            Dictionary with 'role' and 'content'
    """
    if isinstance(message, schema.SystemMessage):
        role = "system"
    elif isinstance(message, schema.HumanMessage):
        role = "user"
    elif isinstance(message, schema.AIMessage):
        role = "assistant"
    else:
        role = "user"  # Default fallback

    return {"role": role, "content": message.content}
