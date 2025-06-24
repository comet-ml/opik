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

    return langchain_messages
