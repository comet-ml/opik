from typing import Callable

from google.adk import models as adk_models
from google.genai import types as genai_types

from opik import LLMProvider

OPIK_USAGE_METADATA_KEY = "opik_usage"


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

    response.custom_metadata[OPIK_USAGE_METADATA_KEY] = usage_metadata
    response.custom_metadata["provider"] = LLMProvider.GOOGLE_AI
    response.custom_metadata["model_version"] = generate_content_response.model_version

    return response
