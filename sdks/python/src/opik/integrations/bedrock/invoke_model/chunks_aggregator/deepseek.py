"""DeepSeek chunk aggregator."""

import json
import logging
from typing import Any, Dict, List

from .. import usage_converters

LOGGER = logging.getLogger(__name__)


class DeepSeekAggregator:
    """
    Aggregator for DeepSeek streaming format.

    Returns DeepSeek's native output format with Bedrock usage.

    DeepSeek chunk structure:
    - choices[0].message.reasoning_content: Reasoning text (stream of thought)
    - choices[0].message.content: Final content (might be null during streaming)
    - choices[0].stop_reason: Stop reason
    - amazon-bedrock-invocationMetrics: Bedrock metrics
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate DeepSeek chunks into native DeepSeek output with Bedrock usage."""
        LOGGER.debug("DeepSeek aggregator processing %d items", len(items))

        reasoning_content = ""
        content = ""
        stop_reason = None
        input_tokens = 0
        output_tokens = 0

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])

                # Extract reasoning and content from choices
                if "choices" in chunk_data and chunk_data["choices"]:
                    choice = chunk_data["choices"][0]

                    # Extract message
                    if "message" in choice and choice["message"]:
                        message = choice["message"]

                        # Extract reasoning content (stream of thought)
                        if (
                            "reasoning_content" in message
                            and message["reasoning_content"]
                        ):
                            reasoning_content += message["reasoning_content"]

                        # Extract final content
                        if "content" in message and message["content"]:
                            content += message["content"]

                    # Extract stop reason
                    if "stop_reason" in choice and choice["stop_reason"]:
                        stop_reason = choice["stop_reason"]
                        LOGGER.debug("DeepSeek stop_reason: %s", stop_reason)

                # Use bedrock metrics as authoritative source
                metrics = chunk_data.get("amazon-bedrock-invocationMetrics", {})
                if metrics:
                    input_tokens = metrics.get("inputTokenCount", input_tokens)
                    output_tokens = metrics.get("outputTokenCount", output_tokens)
                    LOGGER.debug(
                        "DeepSeek bedrock metrics: input=%d, output=%d",
                        input_tokens,
                        output_tokens,
                    )

            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("DeepSeek aggregator error processing chunk: %s", e)
                continue

        final_content = content if content else reasoning_content
        LOGGER.debug(
            "DeepSeek aggregated: reasoning=%d chars, content=%d chars, final=%d chars, input_tokens=%d, output_tokens=%d",
            len(reasoning_content),
            len(content),
            len(final_content),
            input_tokens,
            output_tokens,
        )

        # Convert to Bedrock usage format using shared converter
        if input_tokens > 0 or output_tokens > 0:
            bedrock_usage = usage_converters.openai_to_bedrock_usage(
                {"prompt_tokens": input_tokens, "completion_tokens": output_tokens}
            )
        else:
            bedrock_usage = usage_converters.deepseek_to_bedrock_usage({})

        # Return DeepSeek's native output format with Bedrock usage
        return {
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "reasoning_content": reasoning_content,
                        "content": final_content,
                    },
                    "stop_reason": stop_reason,
                }
            ],
            "usage": bedrock_usage,
        }
