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


def try_get_proxy_response_cost(
    model_response: "litellm.types.utils.ModelResponse",
) -> Optional[float]:
    """The cost a LiteLLM *proxy* reported for this call, or None if not proxied.

    Deliberately not ``_hidden_params["response_cost"]``, which LiteLLM's ``@client``
    wrapper sets on *every* completion, proxied or not. Since the backend prefers a
    client-supplied cost over its own table, reading that would switch every ADK
    LiteLLM span from Opik's pricing to LiteLLM's as a side effect - and a LiteLLM
    cache hit reports an explicit ``0.0``, which would suppress the backend's own
    calculation rather than fall through to it.

    The reliable proxy signal is the ``x-litellm-response-cost`` header, which only a
    LiteLLM proxy sends and which LiteLLM folds into
    ``_hidden_params["additional_headers"]``. Its own
    ``get_response_cost_from_hidden_params`` reads exactly that, so the header key and
    the dict/BaseModel handling stay LiteLLM's business rather than ours.

    Keying on the header rather than on a ``litellm_proxy/`` prefix also covers a
    proxy addressed as ``openai/<alias>`` with ``api_base`` pointed at it: same
    unpriceable alias echoed back, same missing cost, and the prefix says "openai".
    Verified against a real proxy - present for both routings, absent for a direct
    call.

    Never raises: the conversion this runs inside is ADK's, so a LiteLLM-side change
    has to degrade to "no cost" rather than cost the whole response.
    """
    hidden_params = getattr(model_response, "_hidden_params", None)
    if hidden_params is None:
        return None

    try:
        from litellm import cost_calculator

        raw_cost = cost_calculator.get_response_cost_from_hidden_params(hidden_params)
    except Exception:
        LOGGER.debug("Failed to read the LiteLLM proxy response cost", exc_info=True)
        return None

    if raw_cost is None:
        return None

    # nan/inf serialize to bare NaN/Infinity, which is not valid JSON, so letting one
    # through would risk the span it rides on rather than just the cost.
    if not math.isfinite(raw_cost):
        LOGGER.debug("Ignoring non-finite LiteLLM response cost: %r", raw_cost)
        return None

    return raw_cost


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

        # Only a proxied call yields a cost here; a direct one keeps Opik's own
        # pricing, which this must not quietly take over. See
        # try_get_proxy_response_cost.
        response_cost = try_get_proxy_response_cost(model_response)
        LOGGER.debug(
            "generate_content_response_decorator: proxy response_cost=%s",
            response_cost,
        )
        if response_cost is not None:
            result.custom_metadata["opik_response_cost"] = response_cost

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
