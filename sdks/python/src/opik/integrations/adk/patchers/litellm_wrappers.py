import logging
import math
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


def try_get_response_cost(
    model_response: "litellm.types.utils.ModelResponse",
) -> Optional[float]:
    """Reads the cost LiteLLM computed for this call off the raw response.

    Opik's price table is keyed on (provider, model), which can never resolve a
    ``litellm_proxy/<alias>`` route: "litellm_proxy" is not an ``opik.LLMProvider``
    and the alias is not a real model name (the OpenAI-compatible spec requires the
    response to echo the requested model, so no proxy config can fix that either).
    LiteLLM does know the real cost for those calls, so we forward it as the span's
    cost instead of estimating one.

    ``_hidden_params`` is LiteLLM's own documented place for this and the only one:
    ``litellm.completion_cost()`` recomputes from the price map, which is precisely
    what fails for a proxy alias. Being a pydantic private attribute it is also
    absent from ``to_dict()``, so it has to be read off the object - kept to this
    single guarded access so a LiteLLM-side change degrades to "no cost", not a
    raise inside ADK's response conversion.
    """
    hidden_params = getattr(model_response, "_hidden_params", None)
    if not isinstance(hidden_params, dict):
        return None

    raw_cost = hidden_params.get("response_cost", None)
    if raw_cost is None:
        return None

    try:
        cost = float(raw_cost)
    except (TypeError, ValueError):
        LOGGER.debug("Failed to parse LiteLLM response cost from value: %r", raw_cost)
        return None

    # nan/inf serialize to bare NaN/Infinity, which is not valid JSON, so letting one
    # through would risk the span it rides on rather than just the cost.
    if not math.isfinite(cost):
        LOGGER.debug("Ignoring non-finite LiteLLM response cost: %r", raw_cost)
        return None

    return cost


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

        response_cost = try_get_response_cost(model_response)
        provider_and_model = model_response_dict.get("provider_and_model", None)
        LOGGER.debug(
            "generate_content_response_decorator: provider_and_model=%s, response_cost=%s",
            provider_and_model,
            response_cost,
        )

        if provider_and_model is None and response_cost is None:
            return result

        if result.custom_metadata is None:
            LOGGER.debug(
                "generate_content_response_decorator: result.custom_metadata is None, creating new custom metadata"
            )
            result.custom_metadata = {}

        if response_cost is not None:
            result.custom_metadata["opik_response_cost"] = response_cost

        if provider_and_model is not None:
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
