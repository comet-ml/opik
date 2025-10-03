"""Claude/Anthropic chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from .. import usage_converters
from .base import ChunkAggregator

LOGGER = logging.getLogger(__name__)


class ClaudeAggregator(ChunkAggregator):
    """
    Aggregator for Claude/Anthropic streaming format.

    Returns Claude's native message format with content blocks,
    with usage standardized to Bedrock format (camelCase).

    Claude chunk structure uses snake_case:
    - message_start: Contains role and initial usage
    - content_block_start: Content block metadata
    - content_block_delta: Text chunks in delta.text
    - content_block_stop: End of content block
    - message_delta: Stop reason and updated usage
    - message_stop: amazon-bedrock-invocationMetrics
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Claude chunks into native Claude message structure with Bedrock usage."""
        LOGGER.debug("Claude aggregator processing %d items", len(items))

        content_blocks = []
        current_text = ""
        role = "assistant"
        stop_reason = None
        input_tokens = 0
        output_tokens = 0

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])
                chunk_type = chunk_data.get("type", "")

                if chunk_type == "message_start":
                    message = chunk_data.get("message", {})
                    role = message.get("role", "assistant")
                    usage = message.get("usage", {})
                    input_tokens = usage.get("input_tokens", 0)
                    output_tokens = usage.get("output_tokens", 0)
                    LOGGER.debug(
                        "Claude message_start: input_tokens=%d, output_tokens=%d",
                        input_tokens,
                        output_tokens,
                    )

                elif chunk_type == "content_block_delta":
                    delta = chunk_data.get("delta", {})
                    if "text" in delta:
                        current_text += delta["text"]

                elif chunk_type == "content_block_stop":
                    if current_text:
                        content_blocks.append({"type": "text", "text": current_text})
                        current_text = ""

                elif chunk_type == "message_delta":
                    delta = chunk_data.get("delta", {})
                    if "stop_reason" in delta:
                        stop_reason = delta["stop_reason"]
                    usage = chunk_data.get("usage", {})
                    if "output_tokens" in usage:
                        output_tokens = usage["output_tokens"]
                        LOGGER.debug(
                            "Claude message_delta: output_tokens=%d", output_tokens
                        )

                elif chunk_type == "message_stop":
                    metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                    if metrics:
                        input_tokens = metrics.get("inputTokenCount", input_tokens)
                        output_tokens = metrics.get("outputTokenCount", output_tokens)
                        LOGGER.debug(
                            "Claude bedrock metrics: input=%d, output=%d",
                            input_tokens,
                            output_tokens,
                        )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("Claude aggregator error processing chunk: %s", e)
                continue

        # Add any remaining text
        if current_text:
            content_blocks.append({"type": "text", "text": current_text})

        total_text = "".join(
            block["text"] for block in content_blocks if block["type"] == "text"
        )
        LOGGER.debug(
            "Claude aggregated: %d chars, input_tokens=%d, output_tokens=%d",
            len(total_text),
            input_tokens,
            output_tokens,
        )

        # Convert to Bedrock usage format using shared converter
        bedrock_usage = usage_converters.anthropic_to_bedrock_usage(
            {"input_tokens": input_tokens, "output_tokens": output_tokens}
        )

        # Return Claude's native format with Bedrock usage
        return {
            "role": role,
            "content": content_blocks,
            "stop_reason": stop_reason,
            "usage": bedrock_usage,
        }
