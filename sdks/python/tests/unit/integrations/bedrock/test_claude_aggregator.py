import json
from typing import Any, Dict, List, Optional

import opik
from opik import llm_usage
from opik.integrations.bedrock.invoke_model import chunks_aggregator


def _chunk(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


def _stream(
    message_start_usage: Dict[str, Any],
    invocation_metrics: Dict[str, Any],
    message_delta_usage: Optional[Dict[str, Any]] = None,
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
                "usage": (
                    {"output_tokens": 7}
                    if message_delta_usage is None
                    else message_delta_usage
                ),
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

    def test_aggregate_chunks__message_delta_reports_cache_tokens__delta_wins(self):
        # Anthropic reports running totals on message_delta. When it carries cache
        # counters they supersede the message_start values.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
            message_delta_usage={
                "output_tokens": 7,
                "cache_creation_input_tokens": 2048,
                "cache_read_input_tokens": 8192,
            },
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 2048
        assert usage["cacheReadInputTokens"] == 8192

    def test_aggregate_chunks__metrics_report_cache_tokens__terminal_metrics_win(self):
        # amazon-bedrock-invocationMetrics is the terminal, authoritative record, so it
        # replaces anything message_start or message_delta reported.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={
                "inputTokenCount": 15,
                "outputTokenCount": 9,
                "cacheWriteInputTokenCount": 64,
                "cacheReadInputTokenCount": 128,
            },
            message_delta_usage={
                "output_tokens": 7,
                "cache_creation_input_tokens": 2048,
                "cache_read_input_tokens": 8192,
            },
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheWriteInputTokens"] == 64
        assert usage["cacheReadInputTokens"] == 128
        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9

    def test_aggregate_chunks__usage_built_for_backend__cache_counts_survive(self):
        # Covers the rest of the path the decorator takes for a streamed response:
        # BedrockAggregatedResponse.usage -> build_opik_usage -> BedrockUsage ->
        # to_backend_compatible_full_usage_dict. A regression anywhere in that chain
        # would put the cache counts back to zero on the span.
        chunks = _stream(
            message_start_usage={
                "input_tokens": 12,
                "output_tokens": 0,
                "cache_creation_input_tokens": 1024,
                "cache_read_input_tokens": 4096,
            },
            invocation_metrics={},
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)
        opik_usage = llm_usage.build_opik_usage(
            provider=opik.LLMProvider.BEDROCK, usage=response.usage
        )
        backend_usage = opik_usage.to_backend_compatible_full_usage_dict()

        assert backend_usage["original_usage.cacheWriteInputTokens"] == 1024
        assert backend_usage["original_usage.cacheReadInputTokens"] == 4096
        assert backend_usage["original_usage.inputTokens"] == 12
        assert backend_usage["original_usage.outputTokens"] == 7
