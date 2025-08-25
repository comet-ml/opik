import logging
from functools import wraps
from typing import Any, Callable, Optional, Tuple, Union, TYPE_CHECKING

from google.adk import models as adk_models

import opik

if TYPE_CHECKING:
    import litellm

LOGGER = logging.getLogger(__name__)


def parse_provider_and_model(
    model: str,
) -> Tuple[Optional[Union[opik.LLMProvider, str]], str]:
    parts = model.split("/", 1)
    if len(parts) != 2:
        return None, parts[0]

    provider = parts[0]
    try:
        provider = opik.LLMProvider(provider)
    except ValueError:
        pass
    return provider, parts[1]


def generate_content_response_decorator(func: Callable) -> Callable:
    @wraps(func)
    def wrapper(*args: Any, **kwargs: Any) -> adk_models.LlmResponse:
        """
        This wrapper puts token usage data into custom metadata to use it later
        """
        LOGGER.debug("generate_content_response_decorator called")
        result = func(*args, **kwargs)
        model_response = args[0]
        model_response_dict = model_response.to_dict()

        provider_and_model = model_response_dict.get("provider_and_model", None)
        LOGGER.debug(
            "generate_content_response_decorator: provider_and_model=%s",
            provider_and_model,
        )

        if provider_and_model is None:
            return result

        if result.custom_metadata is None:
            LOGGER.debug(
                "generate_content_response_decorator: result.custom_metadata is None, creating new custom metadata"
            )
            result.custom_metadata = {}

        provider, model = parse_provider_and_model(provider_and_model)
        LOGGER.debug(
            "generate_content_response_decorator: provider=%s, model=%s",
            provider,
            model,
        )

        result.custom_metadata["opik_usage"] = model_response_dict["usage"]
        result.custom_metadata["provider"] = provider
        result.custom_metadata["model_version"] = model_response_dict.get(
            "model", model
        )
        LOGGER.debug(
            "generate_content_response_decorator finished: result.custom_metadata=%s",
            result.custom_metadata,
        )
        return result

    return wrapper


def litellm_client_acompletion_decorator(func: Callable) -> Callable:
    @wraps(func)
    async def wrapper(*args: Any, **kwargs: Any) -> "litellm.types.utils.ModelResponse":
        """
        this adds more precise provider/model name and it's version
        """
        result = await func(*args, **kwargs)
        result.provider_and_model = kwargs.get("model", None)
        LOGGER.debug(
            "litellm_client_acompletion_decorator called: provider_and_model=%s",
            result.provider_and_model,
        )
        return result

    return wrapper
