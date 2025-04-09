import langchain_openai
from typing import Any


def get_chat_model(
    model_name: str, *args: Any, **kwargs: Any
) -> langchain_openai.ChatOpenAI:
    return langchain_openai.ChatOpenAI(name=model_name, *args, **kwargs)
