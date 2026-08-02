import json
from typing import Any, Dict, List

from opik.integrations.bedrock.invoke_model import chunks_aggregator


def _chunk(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


def _stream(
    message_start_usage: Dict[str, Any],
    invocation_metrics: Dict[str, Any],
) -> List[Dict[str, Any]]:
    return [
        _chunk(
            {
                "type": "message_start",
                "message": {"role": "assistant", "usage": message_start_usage},
            }
        ),
        _chunk({"type": "content_block_delta", "delta": {"text": "hi"}}),
        _chunk({"type": "content_block_stop"}),
        _chunk(
            {
                "type": "message_delta",
                "delta": {"stop_reason": "end_turn"},
                "usage": {"output_tokens": 7},
            }
        ),
        _chunk(
            {
                "type": "message_stop",
                "amazon-bedrock-invocationMetrics": invocation_metrics,
            }
        ),
    ]


class TestClaudeStreamAggregation:
    def test_aggregate_chunks__message_start_reports_cache_tokens__usage_carries_them(
        self,
    ):
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["cacheReadInputTokens"] == 4096
        assert usage["inputTokens"] == 12
        assert usage["outputTokens"] == 7

    def test_aggregate_chunks__no_cache_tokens_reported__usage_reports_zero(self):
        chunks = _stream(
            message_start_usage={"input_tokens": 12, "output_tokens": 0},
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 0
        assert usage["cacheReadInputTokens"] == 0

    def test_aggregate_chunks__invocation_metrics_present__cache_tokens_survive(self):
        # message_stop metrics take precedence for input/output tokens. Cache tokens
        # are only ever reported on message_start, so they must not be reset here.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={"inputTokenCount": 15, "outputTokenCount": 9},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["cacheReadInputTokens"] == 4096
        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9
