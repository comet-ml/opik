from typing import Optional, Any
from . import langchain_chat_model, base_model

DEFAULT_GPT_MODEL_NAME = "gpt-3.5-turbo"


def get(model_name: Optional[str], **model_kwargs: Any) -> base_model.OpikBaseModel:
    if model_name is None:
        model_name = DEFAULT_GPT_MODEL_NAME

    return langchain_chat_model.LangchainChatModel(
        model_name=model_name, **model_kwargs
    )
