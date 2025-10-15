import logging
from typing import Any, Dict, List, Optional

import pydantic
import litellm.types.utils

import opik.logging_messages as logging_messages

LOGGER = logging.getLogger(__name__)


class CompletionChunksAggregated(pydantic.BaseModel):
    choices: List[Dict[str, Any]]
    created: int
    id: str
    model: str
    object: str
    system_fingerprint: Optional[str] = None
    usage: Optional[Dict[str, Any]] = None


def aggregate(
    items: List[litellm.types.utils.ModelResponse],
) -> Optional[CompletionChunksAggregated]:
    """
    Aggregate streaming chunks from LiteLLM completion into a single response.
    
    LiteLLM returns ModelResponse objects for each chunk, similar to OpenAI's
    ChatCompletionChunk format.
    
    Args:
        items: List of streaming chunks (ModelResponse objects)
        
    Returns:
        Aggregated response or None if aggregation fails
    """
    try:
        if not items:
            return None
            
        first_chunk = items[0]

        aggregated_response = {
            "choices": [{"index": 0, "message": {"role": "", "content": ""}}],
            "created": getattr(first_chunk, "created", 0),
            "id": getattr(first_chunk, "id", ""),
            "model": getattr(first_chunk, "model", ""),
            "object": "chat.completion",
            "system_fingerprint": getattr(first_chunk, "system_fingerprint", None),
            "usage": None,
        }

        text_chunks: List[str] = []

        for chunk in items:
            # Access choices - litellm.types.utils.ModelResponse has choices attribute
            if hasattr(chunk, "choices") and chunk.choices:
                choice = chunk.choices[0]
                
                # LiteLLM uses delta for streaming chunks
                if hasattr(choice, "delta") and choice.delta:
                    delta = choice.delta
                    
                    # Extract role from delta
                    if hasattr(delta, "role") and delta.role:
                        if not aggregated_response["choices"][0]["message"]["role"]:
                            aggregated_response["choices"][0]["message"]["role"] = delta.role
                    
                    # Extract content from delta
                    if hasattr(delta, "content") and delta.content:
                        text_chunks.append(delta.content)
                
                # Extract finish_reason
                if hasattr(choice, "finish_reason") and choice.finish_reason:
                    aggregated_response["choices"][0]["finish_reason"] = choice.finish_reason

            # Extract usage information (typically in the last chunk)
            if hasattr(chunk, "usage") and chunk.usage:
                if hasattr(chunk.usage, "model_dump"):
                    aggregated_response["usage"] = chunk.usage.model_dump()
                elif hasattr(chunk.usage, "dict"):
                    aggregated_response["usage"] = chunk.usage.dict()
                elif isinstance(chunk.usage, dict):
                    aggregated_response["usage"] = chunk.usage

        # Combine all text chunks
        aggregated_response["choices"][0]["message"]["content"] = "".join(text_chunks)
        
        result = CompletionChunksAggregated(**aggregated_response)
        return result
        
    except Exception as exception:
        LOGGER.error(
            "Failed to aggregate LiteLLM streaming chunks: %s",
            str(exception),
            exc_info=True,
        )
        return None

