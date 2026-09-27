"""OpenAI chat completion chunk aggregator (gpt-oss, GPT-5.x, GPT-6)."""

import json
import logging
from typing import Any, Dict, List

from .. import usage_converters
from .base import ChunkAggregator

LOGGER = logging.getLogger(__name__)


def _tokens(counts: Any, key: str, current: int) -> int:
    """`counts[key]` if it is an int (not a bool), else `current`: the stream wrapper
    calls the aggregator in `finally`, so a malformed count must not raise."""
    value = counts.get(key) if isinstance(counts, dict) else None
    return value if isinstance(value, int) and not isinstance(value, bool) else current


class OpenAIAggregator(ChunkAggregator):
    """
    Aggregator for the OpenAI chat completion streaming format.

    Returns OpenAI's native chat completion output with Bedrock usage.

    OpenAI chunk structure:
    - choices[0].delta.content: Generated text
    - choices[0].finish_reason: Stop reason
    - usage: Token usage in last chunk (prompt_tokens, completion_tokens)
    - amazon-bedrock-invocationMetrics: Bedrock metrics in last chunk
    """

    def aggregate(self, items: List[Dict[str, Any]]) -> Dict[str, Any]:
        """Aggregate OpenAI chunks into a native chat completion with Bedrock usage."""
        content = ""
        finish_reason = None
        input_tokens = 0
        output_tokens = 0
        model_id = None
        completion_id = None

        for item in items:
            if "chunk" not in item:
                continue

            try:
                chunk_data = json.loads(item["chunk"]["bytes"])
            except (json.JSONDecodeError, KeyError, TypeError) as e:
                LOGGER.debug("OpenAI aggregator error processing chunk: %s", e)
                continue
            if not isinstance(chunk_data, dict):
                continue

            if model_id is None:
                model_id = chunk_data.get("model")
            if completion_id is None:
                completion_id = chunk_data.get("id")

            # Each part is read on its own, so a malformed choice does not drop
            # the usage and metrics that share its chunk
            choices = chunk_data.get("choices")
            choice = choices[0] if isinstance(choices, list) and choices else None
            if isinstance(choice, dict):
                delta = choice.get("delta")
                if isinstance(delta, dict) and isinstance(delta.get("content"), str):
                    content += delta["content"]
                finish_reason = choice.get("finish_reason") or finish_reason

            usage = chunk_data.get("usage")
            input_tokens = _tokens(usage, "prompt_tokens", input_tokens)
            output_tokens = _tokens(usage, "completion_tokens", output_tokens)

            # Use bedrock metrics as authoritative source
            metrics = chunk_data.get("amazon-bedrock-invocationMetrics")
            input_tokens = _tokens(metrics, "inputTokenCount", input_tokens)
            output_tokens = _tokens(metrics, "outputTokenCount", output_tokens)

        bedrock_usage = usage_converters.openai_to_bedrock_usage(
            {"prompt_tokens": input_tokens, "completion_tokens": output_tokens}
        )

        return {
            "id": completion_id,
            "object": "chat.completion",
            "model": model_id,
            "choices": [
                {
                    "index": 0,
                    "message": {"role": "assistant", "content": content},
                    "finish_reason": finish_reason,
                }
            ],
            "usage": bedrock_usage,
        }
