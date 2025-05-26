import logging
from functools import wraps
from typing import Any, Callable, Optional, Tuple, Union, TYPE_CHECKING

from google.adk import models as adk_models

from ... import LLMProvider

if TYPE_CHECKING:
    import litellm
else:
    litellm = None

LOGGER = logging.Logger(__name__)


def parse_provider_and_model(
    model: str,
) -> Tuple[Optional[Union[LLMProvider, str]], str]:
    parts = model.split("/", 1)
    if len(parts) != 2:
        return None, parts[0]

    provider = parts[0]
    try:
        provider = LLMProvider(provider)
    except ValueError:
        pass
    return provider, parts[1]


def generate_content_response_decorator(func: Callable) -> Callable:
    @wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> adk_models.LlmResponse:
        """
        This wrapper puts token usage data into custom metadata to use it later
        """
        result = func(*args, **kwargs)

        model_response = args[0]
        model_response_dict = model_response.to_dict()

        if result.custom_metadata is None:
            result.custom_metadata = {}

        provider, model = parse_provider_and_model(model_response_dict["provider"])

        result.custom_metadata["opik_usage"] = model_response_dict["usage"]
        result.custom_metadata["provider"] = provider
        result.custom_metadata["model_version"] = model_response_dict["model"]

        return result

    return wrapper


def litellm_client_acompletion_decorator(func: Callable) -> Callable:
    @wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> "litellm.types.utils.ModelResponse":
        """
        this adds more precise provider/model name and it's version
        """
        result = await func(*args, **kwargs)
        result.provider = kwargs.get("model", None)
        return result

    return wrapper
