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
import logging
from typing import Any, Dict, List

import pytest

from opik.integrations.bedrock.invoke_model import (
    chunks_aggregator,
    stream_wrappers,
)
from opik.integrations.bedrock.invoke_model.chunks_aggregator.base import (
    updated_token_count,
)


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
        # Booleans are ints in Python, so a boolean count would otherwise
        # overwrite an earlier real one and reach the usage conversion as
        # `True + int`, which is an int of the wrong magnitude rather than an
        # error. One case per provider, each with an earlier integer to keep.
        pytest.param(
            _claude_stream({"outputTokenCount": True}),
            12,
            1,
            id="claude-metrics-bool-output",
        ),
        pytest.param(
            _claude_stream({"inputTokenCount": False, "outputTokenCount": 3}),
            12,
            3,
            id="claude-metrics-bool-input",
        ),
        pytest.param(
            _nova_stream({"inputTokens": True, "outputTokens": 3}, {}),
            0,
            3,
            id="nova-usage-bool-input",
        ),
        pytest.param(
            _mistral_stream(
                {"prompt_tokens": 7, "completion_tokens": 3},
                {"inputTokenCount": 5, "outputTokenCount": True},
            ),
            5,
            3,
            id="mistral-metrics-bool-output",
        ),
        pytest.param(
            _llama_stream({"outputTokenCount": False}),
            7,
            1,
            id="llama-metrics-bool-output",
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


def test_stream_wrapper__null_token_count__stream_completes_and_callback_runs() -> None:
    """The regression as the caller sees it, through the production wrapper.

    The aggregators are called from the stream wrapper's `finally`, so a raise
    anywhere below skips `finally_callback` and leaves the span open: the
    caller's `for ... in stream` ends in `TypeError` instead of `StopIteration`.
    Asserting the counts alone would not reach that, so drive the wrapper.
    """
    recorded: Dict[str, Any] = {}

    def _finally_callback(**kwargs: Any) -> None:
        recorded.update(kwargs)

    chunks = _claude_stream({"inputTokenCount": None, "outputTokenCount": 3})
    stream = stream_wrappers.wrap_invoke_model_with_response_stream_response(
        stream=iter(chunks),
        capture_output=True,
        span_to_end=None,
        trace_to_end=None,
        generations_aggregator=chunks_aggregator.aggregate_chunks_to_dataclass,
        response_metadata={},
        finally_callback=_finally_callback,
    )

    assert list(stream) == chunks

    assert recorded["error_info"] is None
    usage = recorded["output"].usage
    assert usage["inputTokens"] == 12
    assert usage["outputTokens"] == 3
    assert usage["totalTokens"] == 15


def test_updated_token_count__unexpected_type__logged_and_dropped(
    caplog: pytest.LogCaptureFixture,
) -> None:
    """A type that is neither null, boolean nor int is logged, not raised on."""
    caplog.set_level(logging.WARNING, logger=chunks_aggregator.base.LOGGER.name)

    assert updated_token_count("12", 5) == 5
    assert updated_token_count(1.5, 5) == 5

    assert "token count of type str" in caplog.text
    assert "token count of type float" in caplog.text
    # The documented cases stay quiet: they are the shape this PR is about.
    assert updated_token_count(None, 5) == 5
    assert updated_token_count(True, 5) == 5
    assert updated_token_count(9, 5) == 9
    assert caplog.text.count("Ignoring a token count") == 2
