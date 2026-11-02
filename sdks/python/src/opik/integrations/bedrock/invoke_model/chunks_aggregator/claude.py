"""Claude/Anthropic chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from ...tool_input import parse_streamed_tool_input
from .. import usage_converters
from .base import ChunkAggregator, updated_token_count

LOGGER = logging.getLogger(__name__)


def _apply_delta(block: Dict[str, Any], delta: Dict[str, Any], tool_input: str) -> str:
    """Append a content_block_delta to its block; return the tool input streamed so far."""
    if "text" in delta:
        block["text"] = block.get("text", "") + delta["text"]
    elif "thinking" in delta:
        block["thinking"] = block.get("thinking", "") + delta["thinking"]
    elif "signature" in delta:
        block["signature"] = block.get("signature", "") + delta["signature"]
    elif "partial_json" in delta:
        tool_input += delta["partial_json"]
    elif "citation" in delta:
        block["citations"] = (block.get("citations") or []) + [delta["citation"]]
    else:
        LOGGER.debug("Unknown delta type in content_block_delta: %s", list(delta))
    return tool_input


class ClaudeAggregator(ChunkAggregator):
    """
    Aggregator for Claude/Anthropic streaming format.

    Returns Claude's native message format with content blocks,
    with usage standardized to Bedrock format (camelCase).

    Claude chunk structure uses snake_case:
    - message_start: Contains role and initial usage
    - content_block_start: Content block metadata (text, thinking, tool_use id/name)
    - content_block_delta: delta.text, delta.thinking, delta.signature,
      delta.citation or delta.partial_json (tool input), for the block at the same index
    - content_block_stop: End of content block
    - message_delta: Stop reason and updated usage
    - message_stop: amazon-bedrock-invocationMetrics
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate Claude chunks into native Claude message structure with Bedrock usage."""
        LOGGER.debug("Claude aggregator processing %d items", len(items))

        blocks: Dict[int, Dict[str, Any]] = {}
        tool_inputs: Dict[int, str] = {}
        role = "assistant"
        stop_reason = None
        input_tokens = 0
        output_tokens = 0
        cache_creation_input_tokens = 0
        cache_read_input_tokens = 0
        cache_creation: Dict[str, Any] = {}

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
                    input_tokens = updated_token_count(
                        usage.get("input_tokens"), input_tokens
                    )
                    output_tokens = updated_token_count(
                        usage.get("output_tokens"), output_tokens
                    )
                    cache_creation_input_tokens = updated_token_count(
                        usage.get("cache_creation_input_tokens"),
                        cache_creation_input_tokens,
                    )
                    cache_read_input_tokens = updated_token_count(
                        usage.get("cache_read_input_tokens"), cache_read_input_tokens
                    )
                    cache_creation = usage.get("cache_creation") or cache_creation
                    LOGGER.debug(
                        "Claude message_start: input_tokens=%d, output_tokens=%d",
                        input_tokens,
                        output_tokens,
                    )

                elif chunk_type == "content_block_start":
                    index = chunk_data.get("index", 0)
                    block = dict(chunk_data.get("content_block", {}))
                    if block.get("type") == "tool_use":
                        tool_inputs[index] = ""
                    blocks[index] = block

                elif chunk_type == "content_block_delta":
                    index = chunk_data.get("index", 0)
                    delta = chunk_data.get("delta", {})
                    block = blocks.setdefault(index, {"type": "text", "text": ""})
                    tool_input = _apply_delta(block, delta, tool_inputs.get(index, ""))
                    if "partial_json" in delta:
                        tool_inputs[index] = tool_input

                elif chunk_type == "message_delta":
                    delta = chunk_data.get("delta", {})
                    if "stop_reason" in delta:
                        stop_reason = delta["stop_reason"]
                    usage = chunk_data.get("usage", {})
                    output_tokens = updated_token_count(
                        usage.get("output_tokens"), output_tokens
                    )
                    LOGGER.debug(
                        "Claude message_delta: output_tokens=%d", output_tokens
                    )
                    cache_creation_input_tokens = updated_token_count(
                        usage.get("cache_creation_input_tokens"),
                        cache_creation_input_tokens,
                    )
                    cache_read_input_tokens = updated_token_count(
                        usage.get("cache_read_input_tokens"), cache_read_input_tokens
                    )

                elif chunk_type == "message_stop":
                    metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                    if metrics:
                        input_tokens = updated_token_count(
                            metrics.get("inputTokenCount"), input_tokens
                        )
                        output_tokens = updated_token_count(
                            metrics.get("outputTokenCount"), output_tokens
                        )
                        cache_creation_input_tokens = updated_token_count(
                            metrics.get("cacheWriteInputTokenCount"),
                            cache_creation_input_tokens,
                        )
                        cache_read_input_tokens = updated_token_count(
                            metrics.get("cacheReadInputTokenCount"),
                            cache_read_input_tokens,
                        )
                        LOGGER.debug(
                            "Claude bedrock metrics: input=%d, output=%d",
                            input_tokens,
                            output_tokens,
                        )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("Claude aggregator error processing chunk: %s", e)
                continue

        for index, tool_input in tool_inputs.items():
            if blocks[index].get("type") == "tool_use":
                blocks[index]["input"] = parse_streamed_tool_input(
                    tool_input, index, LOGGER
                )

        # Empty text blocks were never logged before; keep it that way.
        content_blocks = [
            blocks[index]
            for index in sorted(blocks)
            if not (
                blocks[index].get("type") == "text" and not blocks[index].get("text")
            )
        ]

        total_text = "".join(
            block["text"] for block in content_blocks if block.get("type") == "text"
        )
        LOGGER.debug(
            "Claude aggregated: %d chars, input_tokens=%d, output_tokens=%d",
            len(total_text),
            input_tokens,
            output_tokens,
        )

        # Convert to Bedrock usage format using shared converter
        bedrock_usage = usage_converters.anthropic_to_bedrock_usage(
            {
                "input_tokens": input_tokens,
                "output_tokens": output_tokens,
                "cache_creation_input_tokens": cache_creation_input_tokens,
                "cache_read_input_tokens": cache_read_input_tokens,
                "cache_creation": cache_creation,
            }
        )

        # Return Claude's native format with Bedrock usage
        return {
            "role": role,
            "content": content_blocks,
            "stop_reason": stop_reason,
            "usage": bedrock_usage,
        }
