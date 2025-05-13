import os
from typing import (
    Any,
    AsyncGenerator,
    Callable,
    Dict,
    Generator,
    List,
    Optional,
    Tuple,
    Union,
)

import opik.types as opik_types
import pydantic
from opik.api_objects import span
from opik import dict_utils

from . import llm_response_wrapper


def convert_adk_base_model_to_dict(value: pydantic.BaseModel) -> Dict[str, Any]:
    """Most ADK objects are Pydantic Base Models"""
    return dict_utils.remove_none_from_dict(
        value.model_dump(mode="json", exclude_unset=True)
    )


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
