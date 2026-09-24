import json
from typing import Any, Dict, List

import pytest

from opik.integrations.bedrock.invoke_model import chunks_aggregator


def _chunk(choices: List[Any], **extra: Any) -> Dict[str, Any]:
    payload = {"object": "chat.completion.chunk", "choices": choices, **extra}
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


@pytest.mark.parametrize(
    "choices, stop_key",
    [
        # Cut from a us.openai.gpt-6-sol stream: text in delta, then finish_reason
        ([{"delta": {"content": "po"}}, {"delta": {"content": "ng"}}], "finish_reason"),
        # A scalar choice or delta is skipped instead of raising out of the stream
        (["delta", {"delta": "po"}, {"delta": {"content": "pong"}}], "finish_reason"),
        # Mistral (Pixtral) chunks keep message and stop_reason
        ([{"message": {"content": "pong"}}], "stop_reason"),
    ],
    ids=["openai-delta", "malformed-choice-skipped", "mistral-message"],
)
def test_aggregate_chunks__openai_or_mistral_chunks__text_stop_key_and_usage_kept(
    choices: List[Any], stop_key: str
) -> None:
    chunks = [_chunk([choice]) for choice in choices]
    chunks.append(
        _chunk([{stop_key: "stop"}], usage={"prompt_tokens": 8, "completion_tokens": 5})
    )

    response = chunks_aggregator.aggregate_chunks_to_dataclass(chunks)

    choice = response.native_response["choices"][0]
    assert choice["message"]["content"] == "pong"
    assert choice[stop_key] == "stop"
    assert response.usage == {"inputTokens": 8, "outputTokens": 5, "totalTokens": 13}


def test_aggregate_chunks__usage_and_invocation_metrics__metrics_win() -> None:
    # The last chunk of a us.openai.gpt-6-sol stream repeats usage and adds
    # amazon-bedrock-invocationMetrics. The counts differ here only to show that
    # the metrics take precedence.
    metrics = {"inputTokenCount": 9, "outputTokenCount": 6}
    chunks = [
        _chunk([{"delta": {"content": "pong"}, "finish_reason": "stop"}]),
        _chunk(
            [],
            usage={"prompt_tokens": 8, "completion_tokens": 5},
            **{"amazon-bedrock-invocationMetrics": metrics},
        ),
    ]

    usage = chunks_aggregator.aggregate_chunks_to_dataclass(chunks).usage

    assert usage == {"inputTokens": 9, "outputTokens": 6, "totalTokens": 15}
