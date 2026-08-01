import json
from typing import Any, Dict, List

from opik.integrations.bedrock.invoke_model.chunks_aggregator import claude


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


class TestClaudeAggregator:
    def test_aggregate__message_start_reports_cache_tokens__cache_tokens_in_usage(self):
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
        )

        usage = claude.ClaudeAggregator().aggregate(chunks)["usage"]

        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["cacheReadInputTokens"] == 4096
        assert usage["inputTokens"] == 12
        assert usage["outputTokens"] == 7

    def test_aggregate__message_start_has_no_cache_tokens__cache_tokens_are_zero(self):
        chunks = _stream(
            message_start_usage={"input_tokens": 12, "output_tokens": 0},
            invocation_metrics={},
        )

        usage = claude.ClaudeAggregator().aggregate(chunks)["usage"]

        assert usage["cacheWriteInputTokens"] == 0
        assert usage["cacheReadInputTokens"] == 0

    def test_aggregate__invocation_metrics_present__cache_tokens_survive_override(self):
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

        usage = claude.ClaudeAggregator().aggregate(chunks)["usage"]

        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["cacheReadInputTokens"] == 4096
        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9
