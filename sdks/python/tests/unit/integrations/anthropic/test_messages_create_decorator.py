import pytest
from anthropic.types import Message, TextBlock, Usage

from opik.decorator import arguments_helpers
from opik.integrations.anthropic import messages_create_decorator

MODEL = "claude-sonnet-4-5"
MESSAGES = [{"role": "user", "content": "Hello"}]


def _track_options() -> arguments_helpers.TrackOptions:
    return arguments_helpers.TrackOptions(
        name=None,
        type="llm",
        tags=None,
        metadata=None,
        capture_input=True,
        ignore_arguments=None,
        capture_output=True,
        generations_aggregator=None,
        flush=False,
        project_name=None,
        create_duplicate_root_span=True,
        source="sdk",
    )


def _create(**kwargs):
    pass


@pytest.fixture
def decorator():
    return messages_create_decorator.AnthropicMessagesCreateDecorator("anthropic")


def test_start_span_inputs_preprocessor__model_in_kwargs__model_set_on_start(
    decorator,
):
    result = decorator._start_span_inputs_preprocessor(
        func=_create,
        track_options=_track_options(),
        args=(),
        kwargs={"model": MODEL, "messages": MESSAGES, "max_tokens": 10},
    )

    assert result.model == MODEL


def test_start_span_inputs_preprocessor__no_model_in_kwargs__model_is_none(decorator):
    result = decorator._start_span_inputs_preprocessor(
        func=_create,
        track_options=_track_options(),
        args=(),
        kwargs={"messages": MESSAGES, "max_tokens": 10},
    )

    assert result.model is None


def test_start_span_inputs_preprocessor__provider_and_tags__unchanged(decorator):
    result = decorator._start_span_inputs_preprocessor(
        func=_create,
        track_options=_track_options(),
        args=(),
        kwargs={"model": MODEL, "messages": MESSAGES, "max_tokens": 10},
    )

    assert result.provider == "anthropic"
    assert result.tags == ["anthropic"]
    assert result.name == "_create"
    assert result.input == {"messages": MESSAGES}
    assert result.metadata == {
        "model": MODEL,
        "max_tokens": 10,
        "created_from": "anthropic",
    }


def test_end_span_inputs_preprocessor__response_model__model_taken_from_response(
    decorator,
):
    response = Message(
        id="msg_1",
        type="message",
        role="assistant",
        model="claude-sonnet-4-5-20250929",
        content=[TextBlock(type="text", text="Hi")],
        stop_reason="end_turn",
        stop_sequence=None,
        usage=Usage(input_tokens=3, output_tokens=2),
    )

    result = decorator._end_span_inputs_preprocessor(
        output=response, capture_output=True, current_span_data=None
    )

    assert result.model == "claude-sonnet-4-5-20250929"
