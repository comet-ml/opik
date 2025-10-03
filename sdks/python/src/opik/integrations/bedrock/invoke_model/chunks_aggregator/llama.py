"""Meta Llama chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from .. import usage_converters
from .base import ChunkAggregator

LOGGER = logging.getLogger(__name__)


class LlamaAggregator(ChunkAggregator):
    """
    Aggregator for Meta Llama streaming format.

    Returns Llama's native output format with Bedrock usage.

    Llama chunk structure:
    - generation: Contains generated text
    - prompt_token_count: Input tokens (only in first chunk)
    - generation_token_count: Cumulative output tokens
    - stop_reason: Stop reason in final chunk
    - amazon-bedrock-invocationMetrics: Final metrics
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Llama chunks into native Llama output with Bedrock usage."""
        LOGGER.debug("Llama aggregator processing %d items", len(items))

        generation_text = ""
        stop_reason = None
        prompt_token_count = 0
        generation_token_count = 0

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])

                # Extract generated text
                if "generation" in chunk_data and chunk_data["generation"]:
                    generation_text += chunk_data["generation"]

                # Extract prompt token count from first chunk
                if (
                    "prompt_token_count" in chunk_data
                    and chunk_data["prompt_token_count"]
                ):
                    prompt_token_count = chunk_data["prompt_token_count"]
                    LOGGER.debug("Llama prompt_token_count: %d", prompt_token_count)

                # Extract generation token count (cumulative)
                if (
                    "generation_token_count" in chunk_data
                    and chunk_data["generation_token_count"]
                ):
                    generation_token_count = chunk_data["generation_token_count"]

                # Extract stop reason
                if "stop_reason" in chunk_data and chunk_data["stop_reason"]:
                    stop_reason = chunk_data["stop_reason"]
                    LOGGER.debug("Llama stop_reason: %s", stop_reason)

                # Use bedrock metrics as authoritative source
                metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                if metrics:
                    prompt_token_count = metrics.get(
                        "inputTokenCount", prompt_token_count
                    )
                    generation_token_count = metrics.get(
                        "outputTokenCount", generation_token_count
                    )
                    LOGGER.debug(
                        "Llama bedrock metrics: input=%d, output=%d",
                        prompt_token_count,
                        generation_token_count,
                    )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("Llama aggregator error processing chunk: %s", e)
                continue

        LOGGER.debug(
            "Llama aggregated: %d chars, prompt_tokens=%d, generation_tokens=%d",
            len(generation_text),
            prompt_token_count,
            generation_token_count,
        )

        # Convert to Bedrock usage format using shared converter
        bedrock_usage = usage_converters.llama_to_bedrock_usage(
            {
                "prompt_token_count": prompt_token_count,
                "generation_token_count": generation_token_count,
            }
        )

        # Return Llama's native output format with Bedrock usage
        return {
            "generation": generation_text,
            "prompt_token_count": prompt_token_count,
            "generation_token_count": generation_token_count,
            "stop_reason": stop_reason,
            "usage": bedrock_usage,
        }
