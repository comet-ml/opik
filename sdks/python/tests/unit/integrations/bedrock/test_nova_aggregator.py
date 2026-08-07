import json
from typing import Any, Dict, List

from opik.integrations.bedrock.invoke_model import chunks_aggregator


def _chunk(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


def _stream(
    reported_usage: Dict[str, Any],
    invocation_metrics: Dict[str, Any],
) -> List[Dict[str, Any]]:
    metadata_chunk: Dict[str, Any] = {"metadata": {"usage": reported_usage}}
    if invocation_metrics:
        metadata_chunk["amazon-bedrock-invocationMetrics"] = invocation_metrics

    return [
        _chunk({"contentBlockDelta": {"delta": {"text": "hi"}}}),
        _chunk({"messageStop": {"stopReason": "end_turn"}}),
        _chunk(metadata_chunk),
    ]


class TestNovaStreamAggregation:
    def test_aggregate_chunks__usage_reports_cache_tokens__cache_tokens_in_usage(self):
        chunks = _stream(
            reported_usage={
                "inputTokens": 12,
                "outputTokens": 7,
                "cacheReadInputTokens": 4096,
                "cacheWriteInputTokens": 1024,
            },
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheReadInputTokens"] == 4096
        assert usage["cacheWriteInputTokens"] == 1024
        assert usage["inputTokens"] == 12
        assert usage["outputTokens"] == 7

    def test_aggregate_chunks__usage_reports_unknown_field__field_is_preserved(self):
        # Bedrock adding a usage field should not require an SDK change to surface it.
        # BedrockUsage allows extras precisely so new provider fields keep working.
        chunks = _stream(
            reported_usage={
                "inputTokens": 12,
                "outputTokens": 7,
                "someFutureTokenCount": 5,
            },
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["someFutureTokenCount"] == 5

    def test_aggregate_chunks__invocation_metrics_present__metrics_win_but_rest_survives(
        self,
    ):
        chunks = _stream(
            reported_usage={
                "inputTokens": 12,
                "outputTokens": 7,
                "cacheReadInputTokens": 4096,
            },
            invocation_metrics={"inputTokenCount": 15, "outputTokenCount": 9},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9
        assert usage["cacheReadInputTokens"] == 4096

    def test_aggregate_chunks__metrics_override_reported_total__total_follows_overrides(
        self,
    ):
        # Nova reports totalTokens alongside its own pre-override counts. Preserving
        # it would leave original_usage.totalTokens disagreeing with the input and
        # output actually logged on the span once invocationMetrics wins.
        chunks = _stream(
            reported_usage={"inputTokens": 12, "outputTokens": 7, "totalTokens": 19},
            invocation_metrics={"inputTokenCount": 15, "outputTokenCount": 9},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9
        assert usage["totalTokens"] == 24

    def test_aggregate_chunks__total_tokens_absent__total_is_derived(self):
        chunks = _stream(
            reported_usage={"inputTokens": 12, "outputTokens": 7},
            invocation_metrics={},
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["totalTokens"] == 19
