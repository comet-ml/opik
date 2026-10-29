"""Amazon Nova chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from ...converse import chunks_aggregator as converse_chunks_aggregator
from .. import usage_converters
from .base import ChunkAggregator, updated_token_count

LOGGER = logging.getLogger(__name__)


class NovaAggregator(ChunkAggregator):
    """
    Aggregator for Amazon Nova streaming format.

    Returns Nova's native output format with Bedrock usage.

    Nova chunk structure uses camelCase:
    - messageStart: Contains role information
    - contentBlockStart: Contains tool call IDs and names
    - contentBlockDelta: Contains text or tool input fragments
    - contentBlockStop: End of content block
    - messageStop: Contains stopReason
    - metadata: Contains usage information with inputTokens/outputTokens
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Nova chunks into native Nova output structure with Bedrock usage."""
        LOGGER.debug("Nova aggregator processing %d items", len(items))

        events: List[Dict[str, Any]] = []
        input_tokens = 0
        output_tokens = 0
        reported_usage: Dict[str, Any] = {}
        metrics_cache_counts: Dict[str, int] = {}

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])
                events.append(chunk_data)

                if "metadata" in chunk_data:
                    if "usage" in chunk_data["metadata"]:
                        reported_usage = chunk_data["metadata"]["usage"]
                        input_tokens = updated_token_count(
                            reported_usage.get("inputTokens"), input_tokens
                        )
                        output_tokens = updated_token_count(
                            reported_usage.get("outputTokens"), output_tokens
                        )
                        LOGGER.debug(
                            "Nova metadata usage: input=%d, output=%d",
                            input_tokens,
                            output_tokens,
                        )

                    # Use bedrock invocation metrics as authoritative source
                    metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                    if metrics:
                        input_tokens = updated_token_count(
                            metrics.get("inputTokenCount"), input_tokens
                        )
                        output_tokens = updated_token_count(
                            metrics.get("outputTokenCount"), output_tokens
                        )
                        # Only integers are taken. A field reported as null would
                        # otherwise replace a real count from metadata.usage and then
                        # be dropped, since the backend usage dict keeps only integers.
                        for metrics_key, usage_key in (
                            ("cacheWriteInputTokenCount", "cacheWriteInputTokens"),
                            ("cacheReadInputTokenCount", "cacheReadInputTokens"),
                        ):
                            metrics_value = metrics.get(metrics_key)
                            if isinstance(metrics_value, int):
                                metrics_cache_counts[usage_key] = metrics_value
                        LOGGER.debug(
                            "Nova bedrock metrics: input=%d, output=%d",
                            input_tokens,
                            output_tokens,
                        )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("Nova aggregator error processing chunk: %s", e)
                continue

        # Nova uses the same content-block events as Converse, including tool use.
        # Its result also carries stopReason, so messageStop is parsed only once.
        converse_result = converse_chunks_aggregator.aggregate_converse_stream_chunks(
            events
        )
        output = converse_result["output"]
        stop_reason = converse_result.get("stopReason")
        LOGGER.debug(
            "Nova aggregated: %d blocks, input_tokens=%d, output_tokens=%d",
            len(output["message"]["content"]),
            input_tokens,
            output_tokens,
        )

        # Convert to Bedrock usage format using shared converter
        bedrock_usage = usage_converters.nova_to_bedrock_usage(
            {
                **reported_usage,
                **metrics_cache_counts,
                "inputTokens": input_tokens,
                "outputTokens": output_tokens,
            }
        )

        # Return Nova's native output format with Bedrock usage
        return {
            "output": output,
            "stopReason": stop_reason,
            "usage": bedrock_usage,
        }
