from typing import Optional, Any

from .litellm import litellm_chat_model
from . import base_model

DEFAULT_GPT_MODEL_NAME = "gpt-5-nano"

_MODEL_CACHE: dict[Any, base_model.OpikBaseModel] = {}


def _make_cache_key(model_name: str, model_kwargs: Any) -> Any:
    items = tuple(sorted(model_kwargs.items()))
    return (model_name, items)


def get(model_name: Optional[str], **model_kwargs: Any) -> base_model.OpikBaseModel:
    if model_name is None:
        model_name = DEFAULT_GPT_MODEL_NAME

    cache_key = _make_cache_key(model_name, model_kwargs)
    if cache_key not in _MODEL_CACHE:
        _MODEL_CACHE[cache_key] = litellm_chat_model.LiteLLMChatModel(
            model_name=model_name, **model_kwargs
        )
    return _MODEL_CACHE[cache_key]
