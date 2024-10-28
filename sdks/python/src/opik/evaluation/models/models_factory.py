from typing import Optional, Any
from . import base_model, litellm_chat_model

DEFAULT_GPT_MODEL_NAME = "gpt-4o"


def get(model_name: Optional[str], **model_kwargs: Any) -> base_model.OpikBaseModel:
    if model_name is None:
        model_name = DEFAULT_GPT_MODEL_NAME

    return litellm_chat_model.LiteLLMChatModel(model_name=model_name, **model_kwargs)
