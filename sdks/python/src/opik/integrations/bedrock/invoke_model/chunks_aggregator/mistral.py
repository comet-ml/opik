"""Mistral (Pixtral) chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from .. import usage_converters
from .base import ChunkAggregator

LOGGER = logging.getLogger(__name__)


class MistralAggregator(ChunkAggregator):
    """
    Aggregator for Mistral (Pixtral) streaming format.

    Returns Mistral's native OpenAI-like output format with Bedrock usage.

    Mistral chunk structure (OpenAI-compatible):
    - choices[0].message.content: Generated text
    - choices[0].stop_reason: Stop reason
    - usage: Token usage in last chunk (prompt_tokens, completion_tokens, total_tokens)
    - amazon-bedrock-invocationMetrics: Bedrock metrics
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Mistral chunks into native Mistral output with Bedrock usage."""
        LOGGER.debug("Mistral aggregator processing %d items", len(items))

        content = ""
        stop_reason = None
        input_tokens = 0
        output_tokens = 0
        model_id = None
        completion_id = None

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])

                # Extract model ID and completion ID from first chunk
                if model_id is None and "model" in chunk_data:
                    model_id = chunk_data["model"]
                if completion_id is None and "id" in chunk_data:
                    completion_id = chunk_data["id"]

                # Extract content from choices
                if "choices" in chunk_data and chunk_data["choices"]:
                    choice = chunk_data["choices"][0]

                    # Extract message content
                    if "message" in choice and choice["message"]:
                        message_content = choice["message"].get("content")
                        if message_content:
                            content += message_content

                    # Extract stop reason
                    if "stop_reason" in choice and choice["stop_reason"]:
                        stop_reason = choice["stop_reason"]
                        LOGGER.debug("Mistral stop_reason: %s", stop_reason)

                # Extract usage from last chunk
                if "usage" in chunk_data and chunk_data["usage"]:
                    usage = chunk_data["usage"]
                    if "prompt_tokens" in usage:
                        input_tokens = usage["prompt_tokens"]
                    if "completion_tokens" in usage:
                        output_tokens = usage["completion_tokens"]
                    LOGGER.debug(
                        "Mistral usage: prompt=%d, completion=%d",
                        input_tokens,
                        output_tokens,
                    )

                # Use bedrock metrics as authoritative source
                metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                if metrics:
                    input_tokens = metrics.get("inputTokenCount", input_tokens)
                    output_tokens = metrics.get("outputTokenCount", output_tokens)
                    LOGGER.debug(
                        "Mistral bedrock metrics: input=%d, output=%d",
                        input_tokens,
                        output_tokens,
                    )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("Mistral aggregator error processing chunk: %s", e)
                continue

        LOGGER.debug(
            "Mistral aggregated: %d chars, input_tokens=%d, output_tokens=%d",
            len(content),
            input_tokens,
            output_tokens,
        )

        # Convert to Bedrock usage format using shared converter
        bedrock_usage = usage_converters.openai_to_bedrock_usage(
            {"prompt_tokens": input_tokens, "completion_tokens": output_tokens}
        )

        # Return Mistral's native OpenAI-like format with Bedrock usage only
        return {
            "id": completion_id,
            "object": "chat.completion",
            "model": model_id,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "stop_reason": stop_reason,
                }
            ],
            "usage": bedrock_usage,
        }
