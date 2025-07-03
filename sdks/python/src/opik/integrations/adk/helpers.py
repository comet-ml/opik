import os
from typing import (
    Any,
    Dict,
)

from google.adk.models import LlmResponse
import opik.types as opik_types
import pydantic


def convert_adk_base_model_to_dict(value: pydantic.BaseModel) -> Dict[str, Any]:
    """Most ADK objects are Pydantic Base Models"""
    return value.model_dump(mode="json", exclude_unset=True, fallback=str)


def get_adk_provider() -> opik_types.LLMProvider:
    use_vertexai = os.environ.get("GOOGLE_GENAI_USE_VERTEXAI", "0").lower() in [
        "true",
        "1",
    ]
    return (
        opik_types.LLMProvider.GOOGLE_VERTEXAI
        if use_vertexai
        else opik_types.LLMProvider.GOOGLE_AI
    )


def has_empty_text_part_content(llm_response: LlmResponse) -> bool:
    if llm_response.content is None or len(llm_response.content.parts) == 0:
        return True

    # to filter out something like this: {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"}}],...}}
    if len(llm_response.content.parts) == 1:
        part = llm_response.content.parts[0]
        if part.text is not None and len(part.text) == 0:
            return True

    return False
