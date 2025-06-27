import dataclasses
import logging
from typing import Any, Callable, Dict, Optional

from google.adk import models as adk_models
from google.genai import types as genai_types

from . import helpers as adk_helpers
from ... import LLMProvider, llm_usage
from ...llm_usage import opik_usage

LOGGER = logging.Logger(__name__)


class LlmResponseCreateWrapper:
    def __init__(
        self,
        wrapped_response_create: Callable[
            [genai_types.GenerateContentResponse], adk_models.LlmResponse
        ],
    ) -> None:
        self.wrapped_response_create = wrapped_response_create

    def __call__(
        self, generate_content_response: genai_types.GenerateContentResponse
    ) -> adk_models.LlmResponse:
        return _wrap_llm_response_create(
            generate_content_response, self.wrapped_response_create
        )


@dataclasses.dataclass
class LLMUsageData:
    opik_usage: opik_usage.OpikUsage
    model: Optional[str]
    provider: Optional[str]


def pop_llm_usage_data(result_dict: Dict[str, Any]) -> Optional[LLMUsageData]:
    """Extracts Opik usage metadata from ADK output and removes it from the result dict."""
    opik_usage_metadata = None
    provider = None
    model = None

    if (custom_metadata := result_dict.get("custom_metadata", None)) is not None:
        if (opik_usage_metadata := custom_metadata.pop("opik_usage", None)) is not None:
            model = custom_metadata.pop("model_version", None)
            if model is not None:
                model = model.split("/")[-1]

            provider = custom_metadata.pop("provider", None)
    else:
        provider = adk_helpers.get_adk_provider()
        model = None

    # in streaming mode ADK returns the usage metadata in the result dict as the last call
    # to the after_model_callback bypassing our patching (no opik_usage)
    if opik_usage_metadata is None:
        if "usage_metadata" in result_dict:
            opik_usage_metadata = result_dict["usage_metadata"]
        else:
            return None

    if provider in [LLMProvider.GOOGLE_AI, LLMProvider.GOOGLE_VERTEXAI]:
        usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=LLMProvider(provider),
            usage=opik_usage_metadata,
            logger=LOGGER,
            error_message="Failed to log token usage from ADK Gemini call",
        )
    # if not google provider was used - usage data will be in OpenAI format
    else:
        usage = llm_usage.try_build_opik_usage_or_log_error(
            provider=LLMProvider.OPENAI,
            usage=opik_usage_metadata,
            logger=LOGGER,
            error_message=f"Failed to log token usage from ADK {provider} call",
        )

    if usage is None:
        return None

    return LLMUsageData(opik_usage=usage, model=model, provider=provider)


def _wrap_llm_response_create(
    generate_content_response: genai_types.GenerateContentResponse,
    wrapped_response_create: Callable[
        [genai_types.GenerateContentResponse], adk_models.LlmResponse
    ],
) -> adk_models.LlmResponse:
    usage_metadata = generate_content_response.usage_metadata
    response = wrapped_response_create(generate_content_response)
    if usage_metadata is None:
        return response

    if response.custom_metadata is None:
        response.custom_metadata = {}

    response.custom_metadata["opik_usage"] = usage_metadata
    response.custom_metadata["provider"] = adk_helpers.get_adk_provider()
    response.custom_metadata["model_version"] = generate_content_response.model_version

    return response
