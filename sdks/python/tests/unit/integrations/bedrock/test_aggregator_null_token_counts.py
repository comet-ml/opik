"""A token count reported as null must not replace a real count, in any aggregator.

`amazon-bedrock-invocationMetrics` and the per-chunk `usage` blocks can carry
`null` for a count that an earlier chunk already reported (see
test_claude_aggregator.py for the recorded Claude shapes). The OpenAI and Claude
aggregators keep the running count in that case; the other three assign the
value through, so the count becomes `None` and the `*_to_bedrock_usage`
conversion raises `TypeError` on `None + int`. That raise happens inside the
stream wrapper's `finally`, so the span is never closed and the caller's
`for ... in stream` loop ends in `TypeError` instead of `StopIteration`.
"""

import json
from typing import Any, Dict, List

import pytest

from opik.integrations.bedrock.invoke_model import chunks_aggregator


def _chunk(payload: Dict[str, Any]) -> Dict[str, Any]:
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


def _claude_stream(metrics: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [
        _chunk(
            {
                "type": "message_start",
                "message": {
                    "role": "assistant",
                    "usage": {"input_tokens": 12, "output_tokens": 1},
                },
            }
        ),
        _chunk({"type": "content_block_delta", "delta": {"text": "hi"}}),
        _chunk({"type": "message_stop", "amazon-bedrock-invocationMetrics": metrics}),
    ]


def _nova_stream(
    usage: Dict[str, Any], metrics: Dict[str, Any]
) -> List[Dict[str, Any]]:
    # nova.py reads the invocation metrics from the chunk that carries
    # `metadata`, so the last chunk holds both.
    return [
        _chunk({"messageStart": {"role": "assistant"}}),
        _chunk({"contentBlockDelta": {"delta": {"text": "hi"}}}),
        _chunk(
            {
                "metadata": {"usage": usage},
                "amazon-bedrock-invocationMetrics": metrics,
            }
        ),
    ]


def _mistral_stream(
    usage: Dict[str, Any], metrics: Dict[str, Any]
) -> List[Dict[str, Any]]:
    return [
        _chunk(
            {
                "id": "cmpl",
                "model": "mistral.large",
                "object": "chat.completion.chunk",
                "choices": [
                    {"message": {"content": "hi"}, "stop_reason": "stop"},
                ],
            }
        ),
        _chunk(
            {
                "id": "cmpl",
                "model": "mistral.large",
                "object": "chat.completion.chunk",
                "choices": [],
                "usage": usage,
            }
        ),
        _chunk({"amazon-bedrock-invocationMetrics": metrics}),
    ]


def _llama_stream(metrics: Dict[str, Any]) -> List[Dict[str, Any]]:
    return [
        _chunk(
            {
                "generation": "hi",
                "prompt_token_count": 7,
                "generation_token_count": 1,
                "stop_reason": "stop",
            }
        ),
        _chunk({"amazon-bedrock-invocationMetrics": metrics}),
    ]


@pytest.mark.parametrize(
    "chunks, expected_input, expected_output",
    [
        pytest.param(
            _claude_stream({"inputTokenCount": None, "outputTokenCount": 9}),
            12,
            9,
            id="claude-metrics-null-input",
        ),
        pytest.param(
            _claude_stream({"outputTokenCount": None}),
            12,
            1,
            id="claude-metrics-null-output",
        ),
        pytest.param(
            _nova_stream(
                {"inputTokens": 7, "outputTokens": 3},
                {"inputTokenCount": None, "outputTokenCount": 3},
            ),
            7,
            3,
            id="nova-metrics-null-input",
        ),
        pytest.param(
            _nova_stream({"inputTokens": None, "outputTokens": 3}, {}),
            0,
            3,
            id="nova-usage-null-input",
        ),
        pytest.param(
            _mistral_stream(
                {"prompt_tokens": 7, "completion_tokens": 3},
                {"inputTokenCount": None, "outputTokenCount": 3},
            ),
            7,
            3,
            id="mistral-metrics-null-input",
        ),
        pytest.param(
            _mistral_stream({"prompt_tokens": None, "completion_tokens": 3}, {}),
            0,
            3,
            id="mistral-usage-null-input",
        ),
        pytest.param(
            _llama_stream({"inputTokenCount": None, "outputTokenCount": 5}),
            7,
            5,
            id="llama-metrics-null-input",
        ),
        pytest.param(
            _llama_stream({"outputTokenCount": None}),
            7,
            1,
            id="llama-metrics-null-output",
        ),
    ],
)
def test_aggregate_chunks__null_token_count__earlier_count_kept(
    chunks: List[Dict[str, Any]], expected_input: int, expected_output: int
) -> None:
    response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

    assert response.usage["inputTokens"] == expected_input
    assert response.usage["outputTokens"] == expected_output
    assert response.usage["totalTokens"] == expected_input + expected_output
