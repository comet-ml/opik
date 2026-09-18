import pytest
from mistralai.models import (
    CompletionChunk,
    CompletionEvent,
    CompletionResponseStreamChoice,
    DeltaMessage,
)

from opik.integrations.mistral import chat_completion_chunks_aggregator

REASONING = "The user asks for the capital of France. It is Paris."
ANSWER = "Paris."


def _event(content, finish_reason=None):
    return CompletionEvent(
        data=CompletionChunk(
            id="b3f1d1f0e8d94f8f9d4b8d7e5a1c2b3d",
            model="magistral-medium-latest",
            choices=[
                CompletionResponseStreamChoice(
                    index=0,
                    delta=DeltaMessage(role="assistant", content=content),
                    finish_reason=finish_reason,
                )
            ],
        )
    )


def test_aggregate__text_deltas__joined_into_one_string():
    result = chat_completion_chunks_aggregator.aggregate(
        [_event("Par"), _event("is."), _event(None, "stop")]
    )

    assert result is not None
    assert result.choices[0]["message"]["content"] == ANSWER
    assert result.choices[0]["finish_reason"] == "stop"


def test_aggregate__chunk_list_deltas__kept_as_content_chunks():
    # Every 1.x SDK accepts a list of text chunks, which is enough to hit the
    # join that used to raise.
    result = chat_completion_chunks_aggregator.aggregate(
        [
            _event([{"type": "text", "text": "Par"}]),
            _event([{"type": "text", "text": "is."}]),
            _event(None, "stop"),
        ]
    )

    assert result is not None, "a chunk-list delta must not make aggregation fail"
    assert result.choices[0]["message"]["content"] == [
        {"type": "text", "text": "Par"},
        {"type": "text", "text": "is."},
    ]
    assert result.choices[0]["message"]["role"] == "assistant"
    assert result.choices[0]["finish_reason"] == "stop"


def test_aggregate__text_before_and_after_a_chunk_list__arrival_order_kept():
    result = chat_completion_chunks_aggregator.aggregate(
        [
            _event("Par"),
            _event([{"type": "text", "text": "is."}]),
            _event(" later"),
            _event(None, "stop"),
        ]
    )

    assert result is not None
    assert result.choices[0]["message"]["content"] == [
        {"type": "text", "text": "Par"},
        {"type": "text", "text": "is."},
        {"type": "text", "text": " later"},
    ]


def test_aggregate__reasoning_model_thinking_chunks__kept_structured():
    # The thinking chunk only exists in newer SDKs; older ones cannot build it.
    try:
        from mistralai.models import ThinkChunk  # noqa: F401
    except ImportError:
        pytest.skip("this mistralai SDK has no thinking chunk")

    result = chat_completion_chunks_aggregator.aggregate(
        [
            _event(
                [
                    {
                        "type": "thinking",
                        "thinking": [{"type": "text", "text": REASONING}],
                    }
                ]
            ),
            _event([{"type": "text", "text": ANSWER}]),
            _event(None, "stop"),
        ]
    )

    assert result is not None
    content = result.choices[0]["message"]["content"]
    assert content[0]["type"] == "thinking"
    assert content[0]["thinking"][0]["text"] == REASONING
    assert content[1] == {"type": "text", "text": ANSWER}
