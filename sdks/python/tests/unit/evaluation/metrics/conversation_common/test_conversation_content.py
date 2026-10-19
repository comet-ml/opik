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
