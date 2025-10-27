import logging
from typing import Any, Dict, List, Optional

import litellm.types.utils

LOGGER = logging.getLogger(__name__)


def _initialize_aggregated_response(
    first_chunk: litellm.types.utils.ModelResponse,
) -> Dict[str, Any]:
    return {
        "choices": [{"index": 0, "message": {"role": "", "content": ""}}],
        "created": getattr(first_chunk, "created", 0),
        "id": getattr(first_chunk, "id", ""),
        "model": getattr(first_chunk, "model", ""),
        "object": "chat.completion",
        "system_fingerprint": getattr(first_chunk, "system_fingerprint", None),
        "usage": None,
    }


def _extract_role_from_delta(delta: Any, current_role: str) -> str:
    if hasattr(delta, "role") and delta.role and not current_role:
        return delta.role
    return current_role


def _extract_content_from_delta(delta: Any) -> Optional[str]:
    if hasattr(delta, "content") and delta.content:
        return delta.content
    return None


def _extract_finish_reason_from_choice(choice: Any) -> Optional[str]:
    if hasattr(choice, "finish_reason") and choice.finish_reason:
        return choice.finish_reason
    return None


def _extract_usage_from_chunk(
    chunk: litellm.types.utils.ModelResponse,
) -> Optional[Dict[str, Any]]:
    if not hasattr(chunk, "usage") or chunk.usage is None:
        return None

    try:
        if hasattr(chunk.usage, "model_dump"):
            usage_dict = chunk.usage.model_dump()
        elif hasattr(chunk.usage, "dict"):
            usage_dict = chunk.usage.dict()
        elif isinstance(chunk.usage, dict):
            usage_dict = chunk.usage
        else:
            return None

        if usage_dict and isinstance(usage_dict, dict):
            filtered_usage = {k: v for k, v in usage_dict.items() if v is not None}
            return filtered_usage if filtered_usage else None
        return None
    except Exception as exception:
        LOGGER.debug(
            "Error extracting usage from streaming chunk: %s",
            str(exception),
            exc_info=True,
        )
        return None


def aggregate(
    items: List[litellm.types.utils.ModelResponse],
) -> Optional[litellm.types.utils.ModelResponse]:
    try:
        if not items:
            return None

        aggregated_response = _initialize_aggregated_response(items[0])
        text_chunks: List[str] = []

        for chunk in items:
            if not hasattr(chunk, "choices") or not chunk.choices:
                continue

            choice = chunk.choices[0]

            if hasattr(choice, "delta") and choice.delta:
                delta = choice.delta

                current_role = aggregated_response["choices"][0]["message"]["role"]
                aggregated_response["choices"][0]["message"]["role"] = (
                    _extract_role_from_delta(delta, current_role)
                )

                content = _extract_content_from_delta(delta)
                if content:
                    text_chunks.append(content)

            finish_reason = _extract_finish_reason_from_choice(choice)
            if finish_reason:
                aggregated_response["choices"][0]["finish_reason"] = finish_reason

            chunk_usage = _extract_usage_from_chunk(chunk)
            if chunk_usage:
                aggregated_response["usage"] = chunk_usage

        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        return litellm.types.utils.ModelResponse(**aggregated_response)

    except Exception as exception:
        LOGGER.error(
            "Failed to aggregate LiteLLM streaming chunks: %s",
            str(exception),
            exc_info=True,
        )
        return None
