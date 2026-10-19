import pytest

from opik import exceptions
from opik.evaluation.metrics.conversation import conversation_content


def test_message_has_text__string_content():
    assert conversation_content.message_has_text({"content": "Hi there!"})
    assert not conversation_content.message_has_text({"content": ""})
    assert not conversation_content.message_has_text({"content": "   "})
    # ``None`` is what an output transform returns for a turn it has nothing to
    # say for, so it is read as "no text" rather than malformed.
    assert not conversation_content.message_has_text({"content": None})


def test_message_has_text__content_parts():
    assert conversation_content.message_has_text(
        {"content": [{"type": "text", "text": "Hi there!"}]}
    )
    assert conversation_content.message_has_text(
        {"content": ["Hi there!", {"type": "image_url", "image_url": "..."}]}
    )
    # A content list whose parts are all non-text carries no text to grade.
    assert not conversation_content.message_has_text(
        {"content": [{"type": "image_url", "image_url": "..."}]}
    )
    assert not conversation_content.message_has_text({"content": []})


def test_message_has_text__non_text_part_types_are_skipped():
    """A dict part without ``text`` carries none, whatever its ``type``.

    Provider part names -- ``tool_use``, ``thinking``, ``reasoning``, media parts,
    or a name no adapter has seen -- are all skipped rather than reported, so
    content that the conversation metrics accept today keeps working.
    """
    assert conversation_content.message_has_text(
        {
            "content": [
                {"type": "tool_use", "id": "call_1", "name": "get_weather"},
                {"type": "thinking", "thinking": "The user wants the fee."},
                {"type": "text", "text": "It is 5%."},
            ]
        }
    )
    assert not conversation_content.message_has_text(
        {"content": [{"type": "tool_use", "id": "call_1"}]}
    )
    assert not conversation_content.message_has_text({"content": [{"type": "mystery"}]})
    assert not conversation_content.message_has_text(
        {"content": [{"anything": "else"}]}
    )


def test_message_has_text__text_part_without_text__raises():
    """A ``type: "text"`` part without a ``text`` key claims to carry text and
    does not, so it is reported rather than skipped: skipping it would silently
    drop an answer."""
    with pytest.raises(exceptions.MetricComputationError):
        conversation_content.message_has_text({"content": [{"type": "text"}]})


def test_message_has_text__unreadable_content__raises():
    with pytest.raises(exceptions.MetricComputationError):
        conversation_content.message_has_text({"content": 42})

    with pytest.raises(exceptions.MetricComputationError):
        conversation_content.message_has_text({"content": [{"text": 42}]})


def test_text_from_parts__joins_only_the_text_parts():
    assert (
        conversation_content.text_from_parts(
            ["first", {"type": "text", "text": "second"}, {"type": "image_url"}]
        )
        == "first\nsecond"
    )
    # Whitespace-only parts carry no text, so they are dropped from the join.
    assert conversation_content.text_from_parts(["", "   "]) == ""
    # Non-text parts are skipped whatever their payload shape.
    assert (
        conversation_content.text_from_parts(
            [
                {"type": "image", "image_url": {"url": "..."}},
                {"type": "input_audio", "input_audio": {"data": "..."}},
                {"type": "tool_use", "name": "get_weather", "input": {}},
                {"type": "thinking", "thinking": "What is the fee?"},
                {"type": "text", "text": "the answer"},
            ]
        )
        == "the answer"
    )
    assert (
        conversation_content.text_from_parts(
            [{"type": "video_url", "video_url": "..."}]
        )
        == ""
    )
