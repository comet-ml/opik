import json
from typing import Any, Dict, List

from opik.integrations.bedrock.invoke_model import chunks_aggregator

_USAGE = {"prompt_tokens": 8, "completion_tokens": 5, "total_tokens": 13}
_INVOCATION_METRICS = {"inputTokenCount": 8, "outputTokenCount": 5}


def _chunk(choices: List[Any], **extra: Any) -> Dict[str, Any]:
    payload = {
        "id": "chatcmpl-1",
        "object": "chat.completion.chunk",
        "model": "us.openai.gpt-6-sol",
        "choices": choices,
        **extra,
    }
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


class TestOpenAIFormatStreamAggregation:
    def test_aggregate_chunks__openai_model_delta_chunks__text_stop_reason_and_usage_kept(
        self,
    ):
        # Shape recorded from us.openai.gpt-6-sol invoke_model_with_response_stream:
        # text in choices[0].delta, finish_reason, usage + metrics on the last chunks.
        chunks = [
            _chunk(
                [{"index": 0, "delta": {"role": "assistant", "content": ""}}],
                usage=None,
            ),
            _chunk([{"index": 0, "delta": {"content": "po"}}], usage=None),
            _chunk([{"index": 0, "delta": {"content": "ng"}}], usage=None),
            _chunk([{"index": 0, "delta": {}, "finish_reason": "stop"}], usage=_USAGE),
            _chunk(
                [],
                usage=_USAGE,
                **{"amazon-bedrock-invocationMetrics": _INVOCATION_METRICS},
            ),
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        choice = response.native_response["choices"][0]
        assert choice["message"]["content"] == "pong"
        assert choice["finish_reason"] == "stop"
        assert response.usage == {
            "inputTokens": 8,
            "outputTokens": 5,
            "totalTokens": 13,
        }

    def test_aggregate_chunks__malformed_choice_chunks__skipped_without_raising(self):
        # A scalar choice or delta is skipped like other malformed chunks, instead of
        # raising out of the stream wrapper before the span is finished.
        chunks = [
            _chunk(["delta"]),
            _chunk([{"index": 0, "delta": "po"}]),
            _chunk([{"index": 0, "delta": {"content": "pong"}}]),
            _chunk([{"index": 0, "delta": {}, "finish_reason": "stop"}], usage=_USAGE),
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        choice = response.native_response["choices"][0]
        assert choice["message"]["content"] == "pong"
        assert choice["finish_reason"] == "stop"
        assert response.usage["totalTokens"] == 13

    def test_aggregate_chunks__mistral_message_chunks__still_aggregated(self):
        chunks = [
            _chunk([{"index": 0, "message": {"role": "assistant", "content": "po"}}]),
            _chunk(
                [
                    {
                        "index": 0,
                        "message": {"role": "assistant", "content": "ng"},
                        "stop_reason": "stop",
                    }
                ],
                **{"amazon-bedrock-invocationMetrics": _INVOCATION_METRICS},
            ),
        ]

        response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

        choice = response.native_response["choices"][0]
        assert choice["message"]["content"] == "pong"
        assert choice["stop_reason"] == "stop"
        assert response.usage["inputTokens"] == 8
        assert response.usage["outputTokens"] == 5
