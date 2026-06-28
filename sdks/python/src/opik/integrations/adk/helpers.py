import logging
import os
from typing import (
    Any,
    Dict,
)

from google.adk.models import LlmResponse
from opik.api_objects.attachment import base64_normalizer
import opik.types as opik_types
import pydantic

LOGGER = logging.getLogger(__name__)


def convert_adk_base_model_to_dict(value: pydantic.BaseModel) -> Dict[str, Any]:
    """Most ADK objects are Pydantic Base Models"""
    dumped = value.model_dump(mode="json", exclude_unset=True, fallback=str)
    # google.genai's pydantic BaseModel emits URL-safe base64 for inline_data
    # bytes; downstream consumers expect the standard alphabet (OPIK-6387).
    base64_normalizer.normalize_urlsafe_base64_images_in_place(dumped)
    return dumped


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


def drop_invocation_output_if_finished(
    open_agents: Dict[str, int],
    last_model_output: Dict[str, Any],
    invocation_id: str,
) -> None:
    """Decrement the open-agent count for ``invocation_id`` and, once the
    invocation's outermost agent has finished (the count reaches zero), drop its
    cached model output.

    A single tracer instance is shared across concurrent invocations, so the
    cached output is keyed by ``invocation_id``. Counting open agents (rather
    than keying cleanup off span-vs-trace) keeps it correct under
    ``distributed_headers``, where the root agent is itself a span.
    """
    remaining = open_agents.get(invocation_id, 1) - 1
    if remaining > 0:
        open_agents[invocation_id] = remaining
    else:
        open_agents.pop(invocation_id, None)
        last_model_output.pop(invocation_id, None)


def has_empty_text_part_content(llm_response: LlmResponse) -> bool:
    try:
        if llm_response.content is None:
            return True

        if not llm_response.content.parts:
            return True

        # to filter out something like this: {"candidates":[{"content":{"parts":[{"text":""}],"role":"model"}}],...}}
        if len(llm_response.content.parts) == 1:
            part = llm_response.content.parts[0]
            if part.text is not None and len(part.text) == 0:
                return True
        return False
    except Exception as e:
        LOGGER.warning(f"Exception in has_empty_text_part_content {e}", exc_info=True)
        return True
