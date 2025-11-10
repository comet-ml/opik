"""Amazon Nova chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from .. import usage_converters
from .base import ChunkAggregator

LOGGER = logging.getLogger(__name__)


class NovaAggregator(ChunkAggregator):
    """
    Aggregator for Amazon Nova streaming format.

    Returns Nova's native output format with Bedrock usage.

    Nova chunk structure uses camelCase:
    - messageStart: Contains role information
    - contentBlockDelta: Contains text in delta.text
    - contentBlockStop: End of content block
    - messageStop: Contains stopReason
    - metadata: Contains usage information with inputTokens/outputTokens
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Nova chunks into native Nova output structure with Bedrock usage."""
        LOGGER.debug("Nova aggregator processing %d items", len(items))

        output_text = ""
        stop_reason = None
        input_tokens = 0
        output_tokens = 0

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])

                if "contentBlockDelta" in chunk_data:
                    delta = chunk_data["contentBlockDelta"].get("delta", {})
                    if "text" in delta:
                        output_text += delta["text"]

                elif "messageStop" in chunk_data:
                    stop_data = chunk_data["messageStop"]
                    if "stopReason" in stop_data:
                        stop_reason = stop_data["stopReason"]
                        LOGGER.debug("Nova stop_reason: %s", stop_reason)

                elif "metadata" in chunk_data:
                    if "usage" in chunk_data["metadata"]:
                        metadata_usage = chunk_data["metadata"]["usage"]
                        input_tokens = metadata_usage.get("inputTokens", 0)
                        output_tokens = metadata_usage.get("outputTokens", 0)
                        LOGGER.debug(
                            "Nova metadata usage: input=%d, output=%d",
                            input_tokens,
                            output_tokens,
                        )

                    # Use bedrock invocation metrics as authoritative source
                    metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                    if metrics:
                        input_tokens = metrics.get("inputTokenCount", input_tokens)
                        output_tokens = metrics.get("outputTokenCount", output_tokens)
                        LOGGER.debug(
                            "Nova bedrock metrics: input=%d, output=%d",
                            input_tokens,
                            output_tokens,
                        )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("Nova aggregator error processing chunk: %s", e)
                continue

        LOGGER.debug(
            "Nova aggregated: %d chars, input_tokens=%d, output_tokens=%d",
            len(output_text),
            input_tokens,
            output_tokens,
        )

        # Convert to Bedrock usage format using shared converter
        bedrock_usage = usage_converters.nova_to_bedrock_usage(
            {"inputTokens": input_tokens, "outputTokens": output_tokens}
        )

        # Return Nova's native output format with Bedrock usage
        return {
            "output": {
                "message": {"role": "assistant", "content": [{"text": output_text}]}
            },
            "stopReason": stop_reason,
            "usage": bedrock_usage,
        }
