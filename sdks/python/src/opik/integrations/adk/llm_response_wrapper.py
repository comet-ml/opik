import dataclasses
import logging
from typing import Callable, Optional, Dict, Any

from google.adk import models as adk_models
from google.genai import types as genai_types

from ... import llm_usage, LLMProvider
from ...llm_usage import opik_usage
from . import helpers as adk_helpers

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
    custom_metadata = result_dict.get("custom_metadata", None)
    if custom_metadata is None:
        return None

    opik_usage_metadata = custom_metadata.pop("opik_usage", None)
    if opik_usage_metadata is None:
        return None

    model = custom_metadata.pop("model_version", None)
    provider = custom_metadata.pop("provider", None)
    usage = llm_usage.try_build_opik_usage_or_log_error(
        provider=LLMProvider(provider),
        usage=opik_usage_metadata,
        logger=LOGGER,
        error_message="Failed to log token usage from ADK Gemini call",
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
