import json
from typing import Any, Dict, List
from unittest import mock

import pytest

from opik.integrations.bedrock.invoke_model import chunks_aggregator, stream_wrappers


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
    @pytest.mark.parametrize(
        "fragments, expected_input",
        [
            (
                ['{"city":', '"Paris", "days": [1, 2]}'],
                {"city": "Paris", "days": [1, 2]},
            ),
            ([], {}),
            (['{"city":'], '{"city":'),
        ],
        ids=["fragmented-json", "no-arguments", "truncated-json"],
    )
    def test_aggregate_chunks__tool_calls__content_and_usage_preserved(
        self, fragments, expected_input
    ):
        chunks = [
            _chunk({"messageStart": {"role": "assistant"}}),
            _chunk(
                {
                    "contentBlockDelta": {
                        "contentBlockIndex": 0,
                        "delta": {"text": "Checking."},
                    }
                }
            ),
            _chunk({"contentBlockStop": {"contentBlockIndex": 0}}),
        ]
        for index, name in enumerate(["get_weather", "get_forecast"], start=1):
            chunks.append(
                _chunk(
                    {
                        "contentBlockStart": {
                            "contentBlockIndex": index,
                            "start": {
                                "toolUse": {"toolUseId": f"tool-{index}", "name": name}
                            },
                        }
                    }
                )
            )
            chunks.extend(
                _chunk(
                    {
                        "contentBlockDelta": {
                            "contentBlockIndex": index,
                            "delta": {"toolUse": {"input": fragment}},
                        }
                    }
                )
                for fragment in fragments
            )
            chunks.append(_chunk({"contentBlockStop": {"contentBlockIndex": index}}))
        chunks.extend(
            [
                _chunk({"messageStop": {"stopReason": "tool_use"}}),
                _chunk(
                    {
                        "metadata": {
                            "usage": {
                                "inputTokens": 12,
                                "outputTokens": 7,
                                "cacheReadInputTokens": 4096,
                            }
                        },
                        "amazon-bedrock-invocationMetrics": {
                            "inputTokenCount": 15,
                            "outputTokenCount": 9,
                        },
                    }
                ),
            ]
        )

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        assert response.native_response == {
            "output": {
                "message": {
                    "role": "assistant",
                    "content": [
                        {"text": "Checking."},
                        {
                            "toolUse": {
                                "toolUseId": "tool-1",
                                "name": "get_weather",
                                "input": expected_input,
                            }
                        },
                        {
                            "toolUse": {
                                "toolUseId": "tool-2",
                                "name": "get_forecast",
                                "input": expected_input,
                            }
                        },
                    ],
                }
            },
            "stopReason": "tool_use",
        }
        assert response.usage == {
            "inputTokens": 15,
            "outputTokens": 9,
            "totalTokens": 24,
            "cacheReadInputTokens": 4096,
        }

    def test_stream_wrapper__tool_only_turn__callback_keeps_tool_call(self):
        chunks = [
            _chunk({"messageStart": {"role": "assistant"}}),
            _chunk(
                {
                    "contentBlockStart": {
                        "contentBlockIndex": 0,
                        "start": {
                            "toolUse": {"toolUseId": "tool-1", "name": "get_time"}
                        },
                    }
                }
            ),
            _chunk({"contentBlockStop": {"contentBlockIndex": 0}}),
            _chunk({"messageStop": {"stopReason": "tool_use"}}),
        ]
        callback = mock.Mock()
        stream = stream_wrappers.wrap_invoke_model_with_response_stream_response(
            stream=iter(chunks),
            capture_output=True,
            span_to_end=mock.Mock(),
            trace_to_end=None,
            generations_aggregator=chunks_aggregator.aggregate_chunks_to_dataclass,
            response_metadata={"RequestId": "request-1"},
            finally_callback=callback,
        )

        assert list(stream) == chunks
        callback.assert_called_once()
        response = callback.call_args.kwargs["output"]
        assert response.native_response["output"]["message"]["content"] == [
            {"toolUse": {"toolUseId": "tool-1", "name": "get_time", "input": {}}}
        ]
        assert response.response_metadata == {"RequestId": "request-1"}
        assert callback.call_args.kwargs["error_info"] is None

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

    def test_aggregate_chunks__metrics_report_cache_tokens__metrics_win(self):
        # amazon-bedrock-invocationMetrics is the terminal record, so its cache counts
        # replace whatever metadata.usage reported earlier. Mirrors the Claude path.
        chunks = _stream(
            reported_usage={
                "inputTokens": 12,
                "outputTokens": 7,
                "cacheReadInputTokens": 4096,
                "cacheWriteInputTokens": 1024,
            },
            invocation_metrics={
                "inputTokenCount": 15,
                "outputTokenCount": 9,
                "cacheReadInputTokenCount": 128,
                "cacheWriteInputTokenCount": 64,
            },
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheReadInputTokens"] == 128
        assert usage["cacheWriteInputTokens"] == 64
        assert usage["inputTokens"] == 15
        assert usage["outputTokens"] == 9

    def test_aggregate_chunks__metrics_report_null_cache__reported_counts_kept(self):
        # A null in the terminal metrics must not replace a real count from
        # metadata.usage, because the backend usage dict keeps only integers and the
        # field would vanish from the span entirely.
        chunks = _stream(
            reported_usage={
                "inputTokens": 12,
                "outputTokens": 7,
                "cacheReadInputTokens": 4096,
                "cacheWriteInputTokens": 1024,
            },
            invocation_metrics={
                "inputTokenCount": 15,
                "outputTokenCount": 9,
                "cacheReadInputTokenCount": None,
                "cacheWriteInputTokenCount": None,
            },
        )

        usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

        assert usage["cacheReadInputTokens"] == 4096
        assert usage["cacheWriteInputTokens"] == 1024
