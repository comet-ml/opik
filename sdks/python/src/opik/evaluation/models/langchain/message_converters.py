from typing import Dict, List, TYPE_CHECKING

if TYPE_CHECKING:
    import langchain_core.messages


def convert_to_langchain_messages(
    messages: List[Dict[str, str]],
) -> List["langchain_core.messages.BaseMessage"]:
    import langchain_core.messages

    langchain_messages = []
    for message in messages:
        role = message["role"]
        content = message["content"]

        if role == "system":
            langchain_messages.append(
                langchain_core.messages.SystemMessage(content=content)
            )
        elif role == "user":
            langchain_messages.append(
                langchain_core.messages.HumanMessage(content=content)
            )
        elif role == "assistant":
            langchain_messages.append(
                langchain_core.messages.AIMessage(content=content)
            )

    return langchain_messages
