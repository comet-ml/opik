from typing import Optional, Any, Dict

from .litellm import litellm_chat_model
from . import base_model

DEFAULT_GPT_MODEL_NAME = "gpt-5-nano"

_MODEL_CACHE: Dict[Any, base_model.OpikBaseModel] = {}


def _freeze(value: Any) -> Any:
    if isinstance(value, dict):
        return frozenset((k, _freeze(v)) for k, v in value.items())
    if isinstance(value, (list, tuple)):
        return tuple(_freeze(v) for v in value)
    if isinstance(value, set):
        return frozenset(_freeze(v) for v in value)
    return value


def _make_cache_key(model_name: str, track: bool, model_kwargs: Dict[str, Any]) -> Any:
    frozen_kwargs = frozenset((k, _freeze(v)) for k, v in model_kwargs.items())
    return (model_name, track, frozen_kwargs)


def get(
    model_name: Optional[str], track: bool = True, **model_kwargs: Any
) -> base_model.OpikBaseModel:
    """
    Get or create a cached LiteLLM chat model instance.

    Args:
        model_name: The name of the model to use. Defaults to DEFAULT_GPT_MODEL_NAME if None.
        track: Whether to track the model calls. When False, disables tracing for this model instance.
            Defaults to True.
        **model_kwargs: Additional keyword arguments to pass to the model constructor.

    Returns:
        A cached or newly created OpikBaseModel instance.
    """
    if model_name is None:
        model_name = DEFAULT_GPT_MODEL_NAME

    cache_key = _make_cache_key(model_name, track, model_kwargs)
    if cache_key not in _MODEL_CACHE:
        _MODEL_CACHE[cache_key] = litellm_chat_model.LiteLLMChatModel(
            model_name=model_name, track=track, **model_kwargs
        )
    return _MODEL_CACHE[cache_key]
