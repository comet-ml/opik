import json
from typing import Any, Dict, List, Optional, Tuple

import pytest

from opik.integrations.bedrock.invoke_model import chunks_aggregator
from opik.integrations.bedrock.invoke_model.chunks_aggregator import format_detector

USAGE = {"usage": {"prompt_tokens": 8, "completion_tokens": 5}}
METRICS = {
    "amazon-bedrock-invocationMetrics": {"inputTokenCount": 9, "outputTokenCount": 6}
}


def _chunk(choices: List[Any], **extra: Any) -> Dict[str, Any]:
    payload = {"object": "chat.completion.chunk", "choices": choices, **extra}
    return {"chunk": {"bytes": json.dumps(payload).encode()}}


def _delta(text: str) -> Dict[str, Any]:
    return _chunk([{"delta": {"content": text}, "finish_reason": None}])


STOP = _chunk([{"delta": {}, "finish_reason": "stop"}])


@pytest.mark.parametrize(
    "last_chunks, finish_reason, tokens",
    [
        # End of a us.openai.gpt-6-sol stream: finish_reason, then usage and
        # metrics with empty choices. The metrics win over usage.
        ([STOP, _chunk([], **USAGE, **METRICS)], "stop", (9, 6)),
        ([_chunk([{"delta": {}, "finish_reason": "stop"}], **USAGE)], "stop", (8, 5)),
        # A malformed choice or delta keeps the rest of its chunk
        ([STOP, _chunk(["oops"], **USAGE, **METRICS)], "stop", (9, 6)),
        (
            [_chunk([{"delta": "oops", "finish_reason": "stop"}], **USAGE)],
            "stop",
            (8, 5),
        ),
        # Cut before finish_reason: still reported under finish_reason, as null
        ([], None, (0, 0)),
    ],
    ids=["metrics-win", "usage", "scalar-choice", "scalar-delta", "no-finish-reason"],
)
def test_aggregate_chunks__openai_chunks__text_finish_reason_and_tokens_kept(
    last_chunks: List[Dict[str, Any]],
    finish_reason: Optional[str],
    tokens: Tuple[int, int],
) -> None:
    response = chunks_aggregator.aggregate_chunks_to_dataclass(
        [_delta("po"), _delta("ng"), *last_chunks]
    )

    choice = response.native_response["choices"][0]
    assert choice["message"]["content"] == "pong"
    assert choice["finish_reason"] == finish_reason
    assert "stop_reason" not in choice
    assert response.usage == {
        "inputTokens": tokens[0],
        "outputTokens": tokens[1],
        "totalTokens": sum(tokens),
    }


@pytest.mark.parametrize(
    "choices, expected_format",
    [
        ([{"delta": {"content": "po"}}], "openai"),
        ([{"message": {"content": "po"}}], "mistral"),
        # A string choice is neither; a key test on it would be a substring test
        (["delta"], "claude"),
        (["message"], "claude"),
    ],
)
def test_detect_format__chat_completion_chunk__routed_by_choice_shape(
    choices: List[Any], expected_format: str
) -> None:
    assert format_detector.detect_format([_chunk(choices)]) == expected_format
