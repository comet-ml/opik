import os
from typing import (
    Any,
    Dict,
)

import opik.types as opik_types
import pydantic


def convert_adk_base_model_to_dict(value: pydantic.BaseModel) -> Dict[str, Any]:
    """Most ADK objects are Pydantic Base Models"""
    return value.model_dump(mode="json", exclude_unset=True)


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
