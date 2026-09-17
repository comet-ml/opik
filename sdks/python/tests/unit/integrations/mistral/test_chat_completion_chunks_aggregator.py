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


def test_aggregate__reasoning_model_chunk_lists__kept_as_content_chunks():
    # The shape magistral models stream: thinking chunks, then the answer text.
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

    assert result is not None, "a chunk-list delta must not make aggregation fail"
    content = result.choices[0]["message"]["content"]
    assert content[0]["type"] == "thinking"
    assert content[0]["thinking"][0]["text"] == REASONING
    assert content[1] == {"type": "text", "text": ANSWER}
    assert result.choices[0]["message"]["role"] == "assistant"
    assert result.choices[0]["finish_reason"] == "stop"
